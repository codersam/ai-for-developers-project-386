# Phase 1 — Scaffolding (Tactical Plan)

Companion to [backend/CLAUDE.md](../CLAUDE.md). Covers steps 1–3 of §11 of the strategic plan.

## Goal

A Spring Boot 3.4 / Java 21 project living under `backend/`, building cleanly, generating Spring controller interfaces + DTOs from `spec/openapi.yaml`, booting against a Dockerized Postgres with Flyway-applied migrations, and returning **404** on `/api/calendar/event_types` (no controllers yet).

## Exit criteria (all must hold)

1. `./gradlew help` runs from `backend/` using the wrapper (no system Gradle needed afterwards).
2. `./gradlew openApiGenerate` produces `build/generated/openapi/src/main/java/com/hexlet/calendar/generated/api/CalendarApi.java` and DTOs under `…/generated/model/`.
3. `./gradlew compileJava` succeeds — generated sources compile against the empty app.
4. `./gradlew bootRun` starts Postgres via Compose, applies Flyway V1 + V2, listens on `:8080`.
5. `curl -i http://localhost:8080/api/calendar/event_types` → `404 Not Found` (mapping not implemented yet, but context-path resolves).
6. `psql` against the running container shows the three tables and one `calendar_config` seed row.
7. Nothing under `build/`, `.gradle/`, or generated sources is tracked by git.

---

## Prerequisites (verify once, before starting)

| Check | Command | Expected |
|---|---|---|
| Docker Desktop running | `docker info` | no error |
| Java 21 reachable (or accept Gradle toolchain auto-provision) | `java -version` | 21.x preferred; if absent, Gradle's toolchain will fetch one |
| System Gradle for one-shot wrapper bootstrap | `gradle -v` | any 8.x; if absent, `brew install gradle` once |
| Port 5432 free | `lsof -nP -iTCP:5432 -sTCP:LISTEN` | empty |
| Port 8080 free | `lsof -nP -iTCP:8080 -sTCP:LISTEN` | empty |

---

## Step 1 — Gradle project skeleton

Working directory: `backend/`.

### 1.1 Create wrapper + minimal settings

1. `gradle wrapper --gradle-version 8.11.1 --distribution-type bin` → produces `gradlew`, `gradlew.bat`, `gradle/wrapper/`.
2. Create `settings.gradle.kts`:
   ```kotlin
   rootProject.name = "calendar-backend"
   ```
3. Create `gradle.properties`:
   ```properties
   org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
   org.gradle.parallel=true
   org.gradle.caching=true
   ```
4. Create `.gitignore`:
   ```
   .gradle/
   build/
   /out/
   *.iml
   .idea/
   .vscode/
   ```

### 1.2 Stub `build.gradle.kts`

Plugins block + Java 21 toolchain only — no dependencies yet. Goal is to confirm the wrapper resolves before pulling in Spring.

```kotlin
plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories { mavenCentral() }
```

### 1.3 Verify

- `./gradlew help` → `BUILD SUCCESSFUL`. Wrapper is now self-sufficient.

---

## Step 2 — OpenAPI generator wiring

### 2.1 Flesh out `build.gradle.kts`

Add the Spring Boot, dependency-management, and openapi-generator plugins; copy the dependency list and `openApiGenerate` task block verbatim from §1 of [CLAUDE.md](../CLAUDE.md). Two additions the strategic plan does not spell out:

- Make sure the generated DTOs see `jackson-databind-nullable` even though `openApiNullable=false` — leave the dependency in place; it's harmless and avoids surprises if any nullable schema is added later.
- Wire `compileJava.dependsOn("openApiGenerate")` AND register the generator output dir on `sourceSets.main.java.srcDir(...)` (both — the dependency forces ordering, the srcDir registers the path with javac).

### 2.2 Create the empty application class

`src/main/java/com/hexlet/calendar/CalendarApplication.java`:

```java
package com.hexlet.calendar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CalendarApplication {
    public static void main(String[] args) {
        SpringApplication.run(CalendarApplication.class, args);
    }
}
```

### 2.3 Verify generator output

1. `./gradlew openApiGenerate` → `BUILD SUCCESSFUL`.
2. Inspect `build/generated/openapi/src/main/java/com/hexlet/calendar/generated/api/CalendarApi.java` — must contain `createEventType`, `listEventTypes`, `getEventType`, `listAvailableSlots`, `createScheduledEvent`, `listScheduledEvents`, `getScheduledEvent` interface methods.
3. Inspect `…/generated/model/` — must contain `EventType`, `CreateEventType`, `ScheduledEvent`, `CreateScheduledEvent`, `TimeSlot`, `TimeSlotsOfTheDay`, `Calendar`, `Error`.
4. `./gradlew compileJava` → `BUILD SUCCESSFUL`.

### 2.4 Risks to watch

- **Generator picks up old artifacts.** If the model package layout changes between versions, `./gradlew clean` first.
- **`useTags=true`** produces `CalendarApi` (one interface) because the spec has a single `Calendar` tag. If a future spec edit splits tags, controllers must implement multiple `*Api` interfaces — note in CLAUDE.md if it happens.
- **Bean Validation on generated DTOs**: `performBeanValidation=true` annotates DTOs with `@Valid`/`@Size`/etc. Controllers must use `@Valid` on `@RequestBody` to trigger them.

---

## Step 3 — Postgres, Flyway, Docker Compose

### 3.1 `compose.yaml`

Copy verbatim from §7 of CLAUDE.md. Confirm the named volume `calendar-pgdata` (so `./gradlew bootRun` survives restarts without reseeding).

### 3.2 `application.yml`

Copy verbatim from §7 of CLAUDE.md. Two things to double-check:

- `server.servlet.context-path: /api` — non-negotiable, the frontend proxy depends on it (see §0 "Frontend ↔ backend proxy" in CLAUDE.md).
- `spring.docker.compose.lifecycle-management: start-and-stop` — Boot starts Postgres on `bootRun`, stops on shutdown. For dev iteration with a long-lived DB, switch to `start-only` later.

### 3.3 Migrations

`src/main/resources/db/migration/V1__init.sql` and `V2__seed_owner_calendar.sql` — copy verbatim from §2 of CLAUDE.md.

Sanity checks before committing:
- `V1` is idempotent on a fresh DB (Flyway guarantees this; do not add `IF NOT EXISTS` to tables — Flyway tracks state itself).
- `CREATE EXTENSION IF NOT EXISTS btree_gist;` works on `postgres:16-alpine` out of the box (extension ships in `postgresql-contrib`, which is bundled in the Alpine image).
- The exclusion constraint syntax (`EXCLUDE USING GIST (utc_range WITH &&)`) compiles only because of the `btree_gist` extension. If you swap images, re-verify.

### 3.4 Verify boot

1. `./gradlew bootRun` (in another terminal). Watch for:
   - `Starting docker compose ... postgres` line.
   - `Successfully applied 2 migrations to schema "public"` from Flyway.
   - `Started CalendarApplication in N seconds` from Spring.
2. In a second terminal:
   ```
   docker compose -f backend/compose.yaml exec postgres \
     psql -U calendar -d calendar -c '\dt'
   ```
   → should list `event_types`, `scheduled_events`, `calendar_config`, `flyway_schema_history`.
3. ```
   docker compose -f backend/compose.yaml exec postgres \
     psql -U calendar -d calendar -c 'SELECT id, owner_name, owner_timezone FROM calendar_config;'
   ```
   → one row, `Alex Owner`, `Europe/Berlin`.
4. `curl -i http://localhost:8080/api/calendar/event_types` → `404` with Spring's default error JSON. (Means the context-path is correct; no controller mapped yet, which is expected.)
5. `curl -i http://localhost:8080/api/actuator/health` → 404 unless actuator is added; ignore for Phase 1.

### 3.5 Risks to watch

- **Volume cruft from prior projects.** If `docker volume ls` shows a stale `calendar-pgdata`, `docker volume rm calendar-pgdata` once.
- **Flyway sees `ddl-auto=validate` on a schema with no entities yet** — that's fine, validate only kicks in once `@Entity` classes exist (Phase 2). Until then JPA doesn't compare anything.
- **Hibernate JDBC time zone**: `hibernate.jdbc.time_zone: UTC` is set in `application.yml`. Without it, `OffsetDateTime` round-trips can shift by the JVM's default zone — this would silently break the slot algorithm later.

---

## File-creation order (single pass, no rework)

```
backend/
├── .gitignore                                          # 1.1
├── settings.gradle.kts                                 # 1.1
├── gradle.properties                                   # 1.1
├── gradlew, gradlew.bat, gradle/wrapper/…              # 1.1 (gradle wrapper)
├── build.gradle.kts                                    # 1.2 stub → 2.1 full
├── compose.yaml                                        # 3.1
├── src/main/java/com/hexlet/calendar/
│   └── CalendarApplication.java                        # 2.2
└── src/main/resources/
    ├── application.yml                                 # 3.2
    └── db/migration/
        ├── V1__init.sql                                # 3.3
        └── V2__seed_owner_calendar.sql                 # 3.3
```

`application-test.yml`, `config/*.java`, entities, services, controllers, mappers, and tests are **out of scope for Phase 1** — they belong to Phase 2.

---

## Commit plan

One commit per step keeps `git bisect` friendly and matches the existing repo cadence (`plan:`, `chore:`, `fix:` prefixes from recent log).

1. `chore(backend): scaffold gradle project with java 21 wrapper`
2. `chore(backend): wire openapi-generator and spring boot 3.4`
3. `feat(backend): add postgres compose, flyway v1+v2, app boots`

---

## Deviations from CLAUDE.md applied during Phase 1

1. **Gradle wrapper version.** STEP1 specified Gradle 8.11.1; that release ships an embedded Kotlin compiler that `IllegalArgumentException`s on Java 26's version string. The local user shell needed `jenv local 21.0` to pin Java 21 for the wrapper bootstrap. Once Java 21 is active, 8.11.1 works. (Alternative: bump wrapper to 9.x, which natively supports Java 26 — left as-is to match CLAUDE.md.)

2. **`scheduled_events` schema — no `STORED GENERATED` column.** The original `utc_range TSTZRANGE GENERATED ALWAYS AS (...) STORED` clause failed Flyway with `ERROR: generation expression is not immutable`, because `timestamptz + interval` is STABLE (DST math depends on session timezone) and Postgres requires generated-column expressions to be IMMUTABLE. Fix:
   - Added explicit `utc_end TIMESTAMPTZ NOT NULL` column.
   - Added `CHECK (utc_end > utc_start)`.
   - Moved the range expression into the EXCLUDE constraint itself: `EXCLUDE USING GIST (tstzrange(utc_start, utc_end, '[)') WITH &&)`. The expression uses only IMMUTABLE functions, so the GiST index accepts it.
   - **Phase 2 implication**: `ScheduledEventEntity` must map `utc_end` as a writable column; `ScheduledEventService.create` must set it from `utcStart + Duration.ofMinutes(durationMinutes)` before persist. The custom overlap queries use `tstzrange(utc_start, utc_end, '[)')` instead of a `utc_range` column reference. CLAUDE.md §2, §3, and §5 have been updated to reflect this.

---

## Hand-off to Phase 2

Phase 2 starts at §11.4 of CLAUDE.md (entities, repositories, mappers) and ends at §11.5 (the `EventTypeController` happy path). Pre-conditions Phase 1 leaves behind:

- `CalendarApi` interface available for controllers to implement.
- Generated DTOs available for MapStruct mappers to target.
- Schema in place for `@Entity` classes to validate against (`ddl-auto=validate` will start enforcing once the first `@Entity` is added).
- Compose-managed Postgres available for ad-hoc manual testing while Phase 2 progresses.
