# Spring Boot Backend — Implementation Plan

## Context

The frontend (Vite + React + Mantine, in [frontend/](frontend/)) is already built against the OpenAPI 3.1 contract at [backend/spec/openapi.yaml](backend/spec/openapi.yaml). It currently talks to a Prism mock; we now need a real Spring Boot service that fulfills the same contract, backed by PostgreSQL with Flyway migrations.

The product brief in [backend/spec/task.txt](backend/spec/task.txt) defines two roles with **no auth**: a predefined owner profile, and guests who book within a 14-day rolling window. **Two bookings cannot share the same time, even across event types.**

Notable contract subtleties:
- The spec defines a `Calendar` schema (owner profile, working hours, breaks, working days) but **no endpoint exposes it** — so the owner's calendar lives server-side as a single seeded `calendar_config` row.
- `/calendar/event_types/{eventTypeId}/available_slots?clientTimeZone=…` returns slots grouped by **client-local date**, with `timeStart` in client-local wall-clock time.
- `POST /calendar/scheduled_events` accepts `(date, time, guestTimezone)` in the guest's zone; the response carries `utcDateStart` (UTC ISO-8601).

### Frontend ↔ backend proxy (per user clarification)

[frontend/vite.config.ts](frontend/vite.config.ts) currently strips `/api` because Prism serves the OpenAPI paths at root. **For the real backend, the `/api` prefix must stay** — the Spring app will set `server.servlet.context-path: /api`, so controllers (which match the spec's `/calendar/...`) effectively serve at `/api/calendar/...`. The mock workflow keeps the rewrite; the real-backend workflow does not.

---

## Stack

| Concern | Choice |
|---|---|
| Build tool | Gradle (Kotlin DSL) |
| Language / JDK | Java 21 LTS |
| Framework | Spring Boot 3.4.x |
| API contract | Contract-first via `org.openapitools.generator` (Gradle plugin), `interfaceOnly=true` |
| Persistence | Spring Data JPA (Hibernate); `ddl-auto=validate` |
| DB | PostgreSQL 16; `btree_gist` extension for the exclusion constraint |
| Migrations | Flyway |
| Mapping | MapStruct (no Lombok) |
| Validation | `spring-boot-starter-validation` (Jakarta Bean Validation) |
| Tests | JUnit 5 + Spring Boot Test + Testcontainers (postgres:16-alpine) |
| Local dev DB | Spring Boot Docker Compose support via `compose.yaml` |
| Collision rule | DB exclusion constraint + application-level pre-check |

---

## Project layout

The `backend/` directory already contains `spec/`. Add the Gradle/Spring app alongside it:

```
backend/
  spec/                                 # existing — OpenAPI 3.1 contract
    openapi.yaml
    task.txt
  build.gradle.kts
  settings.gradle.kts
  gradle.properties                     # org.gradle.jvmargs, java.toolchain.languageVersion=21
  gradle/wrapper/...                    # gradlew, gradlew.bat, wrapper jar
  compose.yaml                          # postgres:16-alpine for local bootRun
  .gitignore                            # .gradle/, build/
  src/
    main/
      java/com/hexlet/calendar/
        CalendarApplication.java
        config/
          OwnerCalendarProperties.java        # @ConfigurationProperties("owner")
          ClockConfig.java                    # @Bean Clock systemUTC(); overridden in tests
          JacksonConfig.java                  # JavaTimeModule, ISO-8601 instants, write zone as Z
        web/
          api/                                # implement generated CalendarApi interface
            EventTypeController.java
            ScheduledEventController.java
            AvailableSlotsController.java
          error/
            ApiExceptionHandler.java          # @RestControllerAdvice → generated ErrorDto
            ApiException.java
            NotFoundException.java
            BadRequestException.java
            ConflictException.java
        domain/
          model/
            EventTypeEntity.java
            ScheduledEventEntity.java
            CalendarConfigEntity.java
          repo/
            EventTypeRepository.java          # JpaRepository
            ScheduledEventRepository.java     # + custom overlap query
            CalendarConfigRepository.java
        service/
          EventTypeService.java
          ScheduledEventService.java
          AvailabilityService.java            # slot-generation orchestration
          CalendarConfigService.java
          IdGenerator.java                    # 'et_…' / 'se_…' prefixed IDs
        time/
          SlotMath.java                       # pure functions; the unit-tested core
        mapping/
          EventTypeMapper.java                # MapStruct
          ScheduledEventMapper.java           # MapStruct
          TimeSlotMapper.java                 # MapStruct (LocalTime ↔ string, etc.)
      resources/
        application.yml
        application-test.yml                  # picked up by @ActiveProfiles("test")
        db/migration/
          V1__init.sql
          V2__seed_owner_calendar.sql
    test/
      java/com/hexlet/calendar/
        time/SlotMathTest.java                # pure unit
        service/AvailabilityServiceTest.java  # unit with fake repos / fixed Clock
        web/AbstractIntegrationTest.java      # Testcontainers + @SpringBootTest
        web/EventTypeControllerIT.java
        web/ScheduledEventControllerIT.java
        web/AvailableSlotsControllerIT.java
        web/ConcurrencyIT.java                # two parallel POSTs hit the exclusion constraint
        web/ErrorMappingIT.java
      resources/
        application-test.yml
```

Generated sources land in `build/generated/openapi/src/main/java` and are added to the `main` source set; gitignored.

---

## 1. Gradle build (`build.gradle.kts`)

Plugins:

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.6"
    id("org.openapi.generator") version "7.10.0"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
```

Dependencies (key ones):

- `org.springframework.boot:spring-boot-starter-web`
- `org.springframework.boot:spring-boot-starter-data-jpa`
- `org.springframework.boot:spring-boot-starter-validation`
- `org.springframework.boot:spring-boot-docker-compose`
- `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql`
- `org.postgresql:postgresql` (runtime)
- `io.swagger.core.v3:swagger-annotations` + `jakarta.validation:jakarta.validation-api` + `org.openapitools:jackson-databind-nullable` (required by generator output)
- `org.mapstruct:mapstruct:1.6.3` + annotationProcessor `mapstruct-processor`
- Test: `spring-boot-starter-test`, `org.testcontainers:junit-jupiter`, `org.testcontainers:postgresql`, `org.springframework.boot:spring-boot-testcontainers`

OpenAPI generator task:

```kotlin
openApiGenerate {
    generatorName.set("spring")
    library.set("spring-boot")
    inputSpec.set("$projectDir/spec/openapi.yaml")
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.toString())
    apiPackage.set("com.hexlet.calendar.generated.api")
    modelPackage.set("com.hexlet.calendar.generated.model")
    configOptions.set(mapOf(
        "interfaceOnly" to "true",
        "useSpringBoot3" to "true",
        "useTags" to "true",
        "useJakartaEe" to "true",
        "dateLibrary" to "java8",
        "openApiNullable" to "false",
        "skipDefaultInterface" to "true",
        "performBeanValidation" to "true",
    ))
}

sourceSets["main"].java.srcDir("${layout.buildDirectory.get()}/generated/openapi/src/main/java")
tasks.named("compileJava") { dependsOn("openApiGenerate") }
```

Why contract-first: the same `openapi.yaml` already drives the frontend types via `openapi-typescript`. Generating Spring interfaces + DTOs from the same file means both compilers catch any drift — no chance of a silently renamed field.

---

## 2. Database schema

### `V1__init.sql`

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE event_types (
  id               TEXT PRIMARY KEY,
  name             TEXT NOT NULL,
  description      TEXT NOT NULL,
  duration_minutes INTEGER NOT NULL CHECK (duration_minutes > 0),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE scheduled_events (
  id               TEXT PRIMARY KEY,
  event_type_id    TEXT NOT NULL REFERENCES event_types(id),
  utc_start        TIMESTAMPTZ NOT NULL,
  duration_minutes INTEGER NOT NULL CHECK (duration_minutes > 0),
  subject          TEXT NOT NULL,
  notes            TEXT NOT NULL,
  guest_name       TEXT NOT NULL,
  guest_email      TEXT NOT NULL,
  guest_timezone   TEXT NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- Materialized half-open range used for the collision exclusion constraint.
  utc_range        TSTZRANGE GENERATED ALWAYS AS (
    tstzrange(utc_start, utc_start + (duration_minutes || ' minutes')::interval, '[)')
  ) STORED,

  CONSTRAINT scheduled_events_no_overlap
    EXCLUDE USING GIST (utc_range WITH &&)
);

CREATE INDEX scheduled_events_utc_start_idx ON scheduled_events (utc_start);
CREATE INDEX scheduled_events_event_type_idx ON scheduled_events (event_type_id);

CREATE TABLE calendar_config (
  id             SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  owner_name     TEXT       NOT NULL,
  owner_email    TEXT       NOT NULL,
  owner_timezone TEXT       NOT NULL,           -- IANA, e.g. 'Europe/Berlin'
  start_of_day   TIME       NOT NULL,
  end_of_day     TIME       NOT NULL,
  working_days   SMALLINT[] NOT NULL,           -- ISO 1=Mon..7=Sun
  breaks         JSONB      NOT NULL            -- [{"timeStart":"12:00:00","duration":60}, ...]
);
```

Key points:
- `'[)'` half-open range — back-to-back slots (09:00–09:30 and 09:30–10:00) don't overlap; even a one-minute overlap does.
- Generated `utc_range` column eliminates drift between `(utc_start, duration_minutes)` and the range used by the constraint.
- `EXCLUDE USING GIST … WITH &&` enforces "no two bookings overlap" atomically at commit, regardless of `event_type_id` — exactly the spec's rule. App-level pre-check still runs for nice 409 errors.

### `V2__seed_owner_calendar.sql`

```sql
INSERT INTO calendar_config
  (id, owner_name, owner_email, owner_timezone, start_of_day, end_of_day, working_days, breaks)
VALUES
  (1, 'Alex Owner', 'owner@example.com', 'Europe/Berlin',
   '09:00', '17:00',
   ARRAY[1,2,3,4,5]::SMALLINT[],
   '[]'::JSONB);
```

(No event types are seeded — the owner creates them via the admin UI.)

---

## 3. JPA entities

- `EventTypeEntity` — `@Id String id`, `@Column(name = "name") String name` (avoid the `eventTypeName` ↔ DB-column gap), `description`, `Integer durationMinutes`, `OffsetDateTime createdAt`. ID generated in service as `et_<slug>_<random6>` so the values stay readable, like the spec example.
- `ScheduledEventEntity` — `@Id String id` (`se_` + 22-char NanoID), `String eventTypeId` (or `@ManyToOne` to `EventTypeEntity`), `OffsetDateTime utcStart`, `Integer durationMinutes`, plus the spec's string fields. The DB-generated `utc_range` column is mapped as `@Column(insertable = false, updatable = false, columnDefinition = "tstzrange") String utcRange` and ignored by app code.
- `CalendarConfigEntity` — single row (id=1). Fields: `String ownerName`, `String ownerEmail`, `ZoneId ownerTimezone` (via `AttributeConverter<ZoneId, String>`), `LocalTime startOfDay`, `LocalTime endOfDay`, `Short[] workingDays` with `@JdbcTypeCode(SqlTypes.ARRAY)`, `List<TimeSlot> breaks` with `@JdbcTypeCode(SqlTypes.JSON)`.

Repositories:
- `EventTypeRepository extends JpaRepository<EventTypeEntity, String>`
- `ScheduledEventRepository extends JpaRepository<ScheduledEventEntity, String>`
  - Custom: `List<ScheduledEventEntity> findOverlapping(Instant windowStart, Instant windowEnd)` — native query: `SELECT … WHERE utc_range && tstzrange(:start, :end, '[)')`.
  - Custom: `boolean existsOverlapping(Instant slotStart, Integer durationMinutes)` for the pre-check.
- `CalendarConfigRepository extends JpaRepository<CalendarConfigEntity, Short>`

---

## 4. Slot-generation algorithm (`AvailabilityService` + `time/SlotMath`)

Pure logic in [time/SlotMath.java](backend/src/main/java/com/hexlet/calendar/time/SlotMath.java); orchestration (DB lookup, mapping) in `AvailabilityService`.

**Inputs:** `EventTypeEntity` (need `durationMinutes`), `CalendarConfigEntity`, the list of overlapping `ScheduledEventEntity`s (one query), `ZoneId clientZone`, `Clock clock`.

**Steps:**

1. Determine the **window in the client's zone**: `LocalDate clientToday = LocalDate.now(clock.withZone(clientZone))`; window is `[clientToday, clientToday.plusDays(13)]`.
2. UTC bounds for the booking-overlap query: `clientToday.atStartOfDay(clientZone).toInstant()` … `clientToday.plusDays(14).atStartOfDay(clientZone).toInstant()`.
3. Iterate every owner-local `LocalDate` whose business hours could produce a slot whose start instant lies inside the window — typically `[ownerDate0 - 1, ownerDate0 + 14]` to be safe, then filter at the end.
4. Skip dates whose `DayOfWeek` is not in `workingDays`.
5. Build candidate slots in owner-local time: step from `startOfDay` to `endOfDay` in steps of `durationMinutes`; reject any slot whose `[s, s+dur)` overlaps any break or whose end exceeds `endOfDay`.
6. Convert `(ownerDate, slotLocalTime)` → `Instant` via `ZonedDateTime.of(...).toInstant()`. `java.time` handles DST: spring-forward gaps shift forward; fall-back ambiguous times pick the earlier offset (the documented `ZonedDateTime.of` behavior).
7. Drop slots whose start instant overlaps any existing scheduled event (linear scan — at most a few hundred items).
8. Drop slots in the past (`< Instant.now(clock)`).
9. Drop slots outside the UTC window from step 2.
10. Convert each surviving instant back to client-local time via `instant.atZone(clientZone)`; group by `LocalDate`, sort by `LocalTime` within each group.
11. Map to `TimeSlotsOfTheDayDto` — `date` is the client-local date, each `timeSlots[].timeStart` is the client-local `HH:mm:ss`, `timeSlots[].duration` is `eventType.durationMinutes`.

**Edge cases (each gets a unit test):**
- DST spring-forward in `Europe/Berlin`: no slot lands in the missing 02:00–03:00 hour.
- DST fall-back: ambiguous local time resolves to earlier offset; UTC instants stay monotonic.
- Owner zone vs. client zone date boundary: a 09:00 Berlin slot on Tuesday lands on Tuesday 03:00 New York, but on Monday 21:00 Honolulu — grouping is by client-local date.
- Breaks: a 12:00–13:00 break removes the 12:00 and 12:30 candidates (and the 11:30 candidate when `durationMinutes=60`, since it would overlap).
- Slot stepping equals event duration (matches the spec example where all `duration: 30` slots step in 30s).
- "Today" in the client zone with `Clock.fixed(...)` mid-morning: past slots are filtered.
- Window endpoints: today and today+13 are included; today+14 is not.

---

## 5. Booking creation (`ScheduledEventService.create`)

Single `@Transactional` method; default `READ_COMMITTED` (the exclusion constraint provides serialization).

1. Bean-validate the generated `CreateScheduledEventDto`.
2. Find `EventTypeEntity` by `eventTypeId` → `404 NotFoundException` if missing.
3. Load `CalendarConfigEntity` (id=1).
4. Parse `guestTimezone` to `ZoneId` → `400` on `DateTimeException`.
5. Compute UTC start: `LocalDateTime.of(date, time).atZone(guestZone).toInstant()`.
6. Validate window in the **guest's** zone: `today_in_guestZone <= date <= today_in_guestZone + 13` → `400`.
7. Validate not in the past → `400`.
8. **Validate slot alignment** by calling `AvailabilityService.listSlots(eventTypeId, guestZone)` and asserting the requested `(date, time)` is among the returned slots. This single call enforces working day, business hours, breaks, and step alignment in one place.
9. Pre-check collision: `scheduledEventRepository.existsOverlapping(utcStart, eventType.durationMinutes)` → `409 ConflictException` "Slot is no longer available" if hit.
10. Build entity, generate `se_…` id, save.
11. If the unique exclusion constraint trips anyway (race between 9 and 10), `DataIntegrityViolationException` whose root cause is Postgres `23P01` is translated to the same 409 by `ApiExceptionHandler`.
12. Map saved entity → `ScheduledEventDto`; `utcDateStart` is the entity's `utcStart` rendered as ISO-8601 with `Z`.

---

## 6. Error mapping (`ApiExceptionHandler`)

`@RestControllerAdvice` returning the generated `ErrorDto { code, message }`; `code` mirrors HTTP status.

| Exception | HTTP | Notes |
|---|---|---|
| `NotFoundException` | 404 | missing event type / scheduled event |
| `BadRequestException` | 400 | window/alignment/timezone/past-slot |
| `MethodArgumentNotValidException` | 400 | bean validation; concatenate field errors into `message` |
| `HttpMessageNotReadableException` | 400 | malformed JSON |
| `ConflictException` | 409 | app-level slot-collision pre-check |
| `DataIntegrityViolationException` (Postgres SQLSTATE `23P01`) | 409 | DB exclusion constraint fallback |
| `Exception` (catch-all) | 500 | log full stack, generic message |

The OpenAPI spec types every non-200 as the same `Error`, so anything we emit slots in via the `default` response branch.

---

## 7. Configuration

### [backend/src/main/resources/application.yml](backend/src/main/resources/application.yml)

```yaml
server:
  port: 8080
  servlet:
    context-path: /api          # so frontend's /api proxy maps cleanly to /calendar/...

spring:
  application:
    name: calendar-backend
  datasource:
    url: jdbc:postgresql://localhost:5432/calendar
    username: calendar
    password: calendar
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate.jdbc.time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration
  threads:
    virtual:
      enabled: true
  docker:
    compose:
      enabled: true
      lifecycle-management: start-and-stop

owner:
  # IANA timezone fallback if calendar_config row is missing the field; DB is authoritative.
  timezone: Europe/Berlin
```

### [backend/src/main/resources/application-test.yml](backend/src/main/resources/application-test.yml)

`spring.docker.compose.enabled: false`; datasource overridden by Testcontainers JDBC URL (`jdbc:tc:postgresql:16-alpine:///calendar`).

### `compose.yaml`

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: calendar
      POSTGRES_USER: calendar
      POSTGRES_PASSWORD: calendar
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U calendar -d calendar"]
      interval: 2s
      timeout: 2s
      retries: 20
    volumes:
      - calendar-pgdata:/var/lib/postgresql/data
volumes:
  calendar-pgdata:
```

`./gradlew bootRun` from `backend/` triggers Spring Boot's Docker Compose support, which starts Postgres, waits for the healthcheck, then boots the app — no separate `docker compose up` step.

---

## 8. Tests

### Unit (no Spring context)
- `SlotMathTest` — covers every edge case in §4: typical Mon–Fri, breaks, collision-driven gaps, DST spring-forward and fall-back in `Europe/Berlin`, owner vs. client zone date boundaries (`Pacific/Auckland`, `Pacific/Honolulu`), past-slot filtering on "today", window endpoints, 60-minute event with 30-minute existing booking.
- `AvailabilityServiceTest` — wires `SlotMath` with fake repos and a fixed `Clock`; verifies the DB lookup window is correct.

### Integration (`@SpringBootTest` + Testcontainers)
`AbstractIntegrationTest` uses `@ServiceConnection` on a `@Container static PostgreSQLContainer<>("postgres:16-alpine")`; Flyway runs against it. A `@TestConfiguration` provides a `Clock.fixed(...)` bean.

- `EventTypeControllerIT` — POST then GET; validation error → 400 with `Error` shape.
- `AvailableSlotsControllerIT` — known seed + fixed Clock → expected 14-day grouping; existing booking is excluded; invalid `clientTimeZone` → 400.
- `ScheduledEventControllerIT`:
  - Happy path: POST → 200; GET by id; list contains it.
  - Outside window → 400.
  - Past slot → 400.
  - Misaligned slot (09:15 with 30-min step) → 400.
  - **Cross-event-type collision**: book A at 10:00, then book B at 10:00 → 409.
- `ConcurrencyIT` — two threads POST the same slot concurrently using a `CountDownLatch`; exactly one succeeds (200), the other gets 409 (`23P01`). This is the strongest test of the DB-level guard.
- `ErrorMappingIT` — malformed JSON, missing required field, unknown event type all return the `ErrorDto` shape.

---

## 9. Frontend wiring (documentation only)

Per user clarification: with the real backend, **`/api` stays in the proxied path**. The Spring app's `server.servlet.context-path: /api` accepts that prefix and routes to controllers serving `/calendar/...`.

Two changes when switching the frontend off Prism:

1. [frontend/vite.config.ts](frontend/vite.config.ts):
   - Change `target: "http://127.0.0.1:4010"` → `target: "http://127.0.0.1:8080"`.
   - **Remove** the `rewrite: (path) => path.replace(/^\/api/, "")` line — that was a Prism-only hack.
2. [frontend/package.json](frontend/package.json):
   - Keep the existing `dev` (with Prism) as `dev:mock` for offline contract work.
   - Add `"dev": "vite"` for the real-backend workflow (assumes `./gradlew bootRun` is running separately).

This is left for the user to apply; the backend implementation does not modify the frontend.

---

## 10. Critical files to be created

- [backend/build.gradle.kts](backend/build.gradle.kts)
- [backend/settings.gradle.kts](backend/settings.gradle.kts)
- [backend/compose.yaml](backend/compose.yaml)
- [backend/src/main/resources/application.yml](backend/src/main/resources/application.yml)
- [backend/src/main/resources/db/migration/V1__init.sql](backend/src/main/resources/db/migration/V1__init.sql)
- [backend/src/main/resources/db/migration/V2__seed_owner_calendar.sql](backend/src/main/resources/db/migration/V2__seed_owner_calendar.sql)
- [backend/src/main/java/com/hexlet/calendar/CalendarApplication.java](backend/src/main/java/com/hexlet/calendar/CalendarApplication.java)
- [backend/src/main/java/com/hexlet/calendar/time/SlotMath.java](backend/src/main/java/com/hexlet/calendar/time/SlotMath.java) — the testable core
- [backend/src/main/java/com/hexlet/calendar/service/AvailabilityService.java](backend/src/main/java/com/hexlet/calendar/service/AvailabilityService.java)
- [backend/src/main/java/com/hexlet/calendar/service/ScheduledEventService.java](backend/src/main/java/com/hexlet/calendar/service/ScheduledEventService.java)
- [backend/src/main/java/com/hexlet/calendar/web/error/ApiExceptionHandler.java](backend/src/main/java/com/hexlet/calendar/web/error/ApiExceptionHandler.java)

(Plus the JPA entities, repositories, MapStruct mappers, three controllers, config classes, and the test files listed in §8.)

---

## 11. Implementation order

1. **Scaffold Gradle project**: `build.gradle.kts`, `settings.gradle.kts`, wrapper, `.gitignore`. Confirm `./gradlew help` works.
2. **Wire openapi-generator**: add the plugin, point it at `spec/openapi.yaml`, add the generated source dir to the main source set, run `./gradlew openApiGenerate` and inspect the generated `CalendarApi` interface and DTOs.
3. **Add Postgres + Flyway + Compose**: write `compose.yaml`, V1 + V2 migrations, `application.yml`. `./gradlew bootRun` should start Postgres, run Flyway, and bring the app up on `:8080` with a 404 on `/api/calendar/event_types` (no controllers yet).
4. **Entities, repositories, mappers**: `EventTypeEntity`, `ScheduledEventEntity`, `CalendarConfigEntity`; the three repositories; MapStruct mappers.
5. **`EventTypeController`** (simplest endpoints): implement `CalendarApi` methods for `createEventType` and `listEventTypes`, with `EventTypeService` in between.
6. **`SlotMath` + unit tests**: build out the pure algorithm and exhaustively test it before any controller depends on it.
7. **`AvailabilityService` + `AvailableSlotsController`**: thin wrapper over `SlotMath` that loads `CalendarConfigEntity` and the overlap window from the DB.
8. **`ScheduledEventController` + `ScheduledEventService`**: includes the validation pipeline and collision pre-check; finishes with the integration tests including `ConcurrencyIT`.
9. **`ApiExceptionHandler`**: by now every throw site is known; map them all to `ErrorDto`.
10. **Polish**: actuator health endpoint, request logging filter, a README in `backend/` describing `bootRun` and the `/api` context-path.

---

## 12. Verification

End-to-end check after implementation:

1. **Build**: `cd backend && ./gradlew clean build` — generates DTOs, compiles, runs all unit + integration tests; Testcontainers spins up Postgres for the IT phase. All tests green.
2. **Boot**: `./gradlew bootRun` — Compose brings up Postgres, Flyway applies V1+V2, app listens on `:8080`. `curl http://localhost:8080/api/calendar/event_types` returns `[]`.
3. **Create event type**:
   ```
   curl -X POST http://localhost:8080/api/calendar/event_types \
     -H 'Content-Type: application/json' \
     -d '{"eventTypeName":"Intro Call","description":"Short chat","durationMinutes":30}'
   ```
   Returns 200 with a generated `eventTypeId` (`et_intro_…`).
4. **List slots**: `curl 'http://localhost:8080/api/calendar/event_types/<id>/available_slots?clientTimeZone=Europe/Berlin'` — returns the 14-day grouping; shape matches the spec example.
5. **Book a slot**: POST to `/api/calendar/scheduled_events` with `(eventTypeId, date, time, guestTimezone, …)` — returns 200 with `utcDateStart` in UTC. Re-GET `/available_slots`: that slot is gone.
6. **Collision**: POST again to the same slot with a *different* event type — returns 409 with `{"code":409,"message":"Slot is no longer available"}`.
7. **Concurrency**: the `ConcurrencyIT` integration test confirms the DB exclusion constraint settles races deterministically.
8. **Frontend**: edit [frontend/vite.config.ts](frontend/vite.config.ts) to point at `127.0.0.1:8080` and drop the `/api` rewrite; `cd frontend && npm run dev` (or the new `dev` script). The full guest + owner flows in the React app run against the real backend.
