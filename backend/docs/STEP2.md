# Phase 2 — Persistence layer + EventType endpoints (Tactical Plan)

Companion to [backend/CLAUDE.md](../CLAUDE.md). Covers steps 4–5 of §11 of the strategic plan: the JPA layer for all three aggregates, one MapStruct mapper, and the `createEventType` + `listEventTypes` endpoints. Phase 1 (scaffold + generator + Postgres+Flyway) is the precondition; see [STEP1.md](STEP1.md).

## Goal

Stand up the persistence layer for all three aggregates so `ddl-auto=validate` passes against the V1 schema, then implement the simplest pair of endpoints — `POST /api/calendar/event_types` and `GET /api/calendar/event_types` — through a thin `EventTypeService` and a MapStruct DTO seam. Repositories are scaffolded for Phase 3 but carry no custom queries yet. `ScheduledEventEntity` and `CalendarConfigEntity` exist solely to satisfy `validate` and to give Phase 3 something to wire against; they have no service, controller, or mapper consumers in this phase.

## Exit criteria (all must hold)

1. `./gradlew clean compileJava` — `BUILD SUCCESSFUL`. MapStruct annotation processor runs and emits `EventTypeMapperImpl` under `build/generated/sources/annotationProcessor/`.
2. `./gradlew bootRun` boots cleanly — Hibernate `validate` finds all three tables and column types matching the entities; no `SchemaManagementException` in logs.
3. `curl -i http://localhost:8080/api/calendar/event_types` → `200 OK` with body `[]`.
4. `curl -X POST http://localhost:8080/api/calendar/event_types -H 'Content-Type: application/json' -d '{"eventTypeName":"Intro Call","description":"Short chat","durationMinutes":30}'` → `200` with `{"eventTypeId":"et_intro-call_xxxxxx","eventTypeName":"Intro Call",...}`. The id matches `^et_[a-z0-9-]+_[a-z0-9]{6}$`.
5. Re-running the GET returns the row just created. Restarting `bootRun` and re-GETting still returns it (persisted).
6. `psql … -c 'SELECT id, name, duration_minutes FROM event_types;'` shows the same row, with the column named `name` (not `event_type_name`) populated.
7. The other four `CalendarApi` methods (`createScheduledEvent`, `getScheduledEventById`, `listScheduledEvents`, `listAvailableSlots`) return `501 Not Implemented` — explicitly stubbed, not 404s.
8. Nothing under `web/error/` yet. Framework-default error mapping is acceptable for Phase 2; the typed `Error` shape arrives with `ApiExceptionHandler` in Phase 3.

---

## Architecture deviation from CLAUDE.md §3 — the controller question

**Decision: one `CalendarController implements CalendarApi`.**

CLAUDE.md §3 sketches three controllers (`EventTypeController`, `ScheduledEventController`, `AvailableSlotsController`). The OpenAPI generator emits exactly **one** interface — `com.hexlet.calendar.generated.api.CalendarApi` — because the spec has a single `Calendar` tag and `useTags=true`. Java only allows one class per interface. The original three-controller layout is therefore unimplementable as-written.

Considered alternatives:

- **B. Three controllers, only one implements `CalendarApi`.** Two-thirds of the surface loses the contract-enforcement guarantee — the whole reason we generate from `openapi.yaml`. Rejected.
- **C. Composition: `CalendarController` delegates each method to a per-domain handler bean.** A is C with one fewer indirection. Rejected.

Trade-off: `CalendarController` accumulates ~6 methods across three domains. Acceptable because each method is one delegating line. The strategic split survives at the **service** layer (`EventTypeService`, `ScheduledEventService`, `AvailabilityService`), which is where the logic lives. If the spec ever splits into multiple tags, the generator emits multiple `*Api` interfaces and we revisit per the original §3 layout.

CLAUDE.md §3's project layout listing is updated in this phase to reflect the single-controller reality (see "Hand-off" below).

---

## Step 1 — Domain primitives (`BreakItem`, `ZoneIdConverter`)

Trivial classes the entities depend on. Compile-only, no behavior to verify.

### 1.1 `domain/model/BreakItem.java`

POJO mirroring the JSONB shape stored in `calendar_config.breaks`:

```
LocalTime timeStart
Integer   duration
```

No-arg constructor + all-args constructor + getters/setters by hand (no Lombok per CLAUDE.md). Spring Boot's auto-configured `JavaTimeModule` round-trips `LocalTime` to ISO-8601 (`"12:00:00"`) automatically — matches the V2 seed shape.

This is the **persistence** shape, distinct from the wire-side `TimeSlot` DTO whose `timeStart` is a `String`. The Phase 3 `TimeSlotMapper` converts at the seam.

### 1.2 `domain/converter/ZoneIdConverter.java`

```
@Converter(autoApply = false)
public class ZoneIdConverter implements AttributeConverter<ZoneId, String> {
    public String convertToDatabaseColumn(ZoneId id)     { return id == null ? null : id.getId(); }
    public ZoneId convertToEntityAttribute(String value) { return value == null ? null : ZoneId.of(value); }
}
```

`autoApply = false` — referenced explicitly via `@Convert(converter = ZoneIdConverter.class)` on the field. `ZoneId` is too generic to globally hijack. `DateTimeException` from `ZoneId.of` surfaces as a `ConversionException` at JPA load — fine for now since the seed row uses a known-valid `Europe/Berlin`.

---

## Step 2 — Three JPA entities

`ddl-auto=validate` is the safety net. Every column name and type must match V1__init.sql. Verify by running `./gradlew bootRun` after this step — if anything mismatches, **fix the entity, not the migration** (V1 is canonical).

### 2.1 `domain/model/EventTypeEntity.java`

```
@Entity @Table(name = "event_types")
String         id                      // @Id; assigned by IdGenerator (no @GeneratedValue)
String         name                    // @Column(name = "name") — DB↔DTO seam
String         description
Integer        durationMinutes         // @Column(name = "duration_minutes")
OffsetDateTime createdAt               // @Column(name = "created_at", insertable = false, updatable = false)
                                       // + @org.hibernate.annotations.Generated(event = INSERT)
                                       //   so Hibernate reloads it after Postgres DEFAULT now() fires
```

### 2.2 `domain/model/ScheduledEventEntity.java`

```
@Entity @Table(name = "scheduled_events")
String         id
String         eventTypeId             // raw String, NOT @ManyToOne
OffsetDateTime utcStart
OffsetDateTime utcEnd                  // writable; service sets it from utcStart + duration
Integer        durationMinutes
String         subject, notes,
               guestName, guestEmail, guestTimezone
OffsetDateTime createdAt               // same @Generated treatment as above
```

Why raw `String eventTypeId`: Phase 3's overlap query operates on UTC ranges, not on the FK. Lazy-loading an `EventTypeEntity` to render a `ScheduledEvent` DTO would just add fetch round-trips without value.

### 2.3 `domain/model/CalendarConfigEntity.java`

```
@Entity @Table(name = "calendar_config")
Short              id                  // @Id; always 1
String             ownerName, ownerEmail
ZoneId             ownerTimezone       // @Convert(converter = ZoneIdConverter.class)
LocalTime          startOfDay, endOfDay
Short[]            workingDays         // @JdbcTypeCode(SqlTypes.ARRAY)
                                       // @Column(name = "working_days", columnDefinition = "smallint[]")
List<BreakItem>    breaks              // @JdbcTypeCode(SqlTypes.JSON)
                                       // @Column(columnDefinition = "jsonb")
```

Decisions resolved here:
- **`workingDays`: `Short[]`, not `List<Short>`.** Hibernate 6's `SqlTypes.ARRAY` works cleanly with arrays of boxed primitives; `List<Short>` would need extra `@CollectionType` plumbing. The field is internal; `AvailabilityService` translates `1..7` ↔ `DayOfWeek` in Phase 3.
- **`breaks`: `List<BreakItem>` with `@JdbcTypeCode(SqlTypes.JSON)`.** Concrete generic type is required; Jackson is on the classpath via the web starter; `JavaTimeModule` handles `LocalTime` ISO-8601 automatically.
- **`ownerTimezone`: `ZoneId` via converter.** Per CLAUDE.md §3. Tiny, isolates `ZoneId.of(...)` parsing, and lets the rest of the code rely on a typed field.

### 2.4 Verify

`./gradlew bootRun` — watch for `Started CalendarApplication`. If Hibernate logs `Wrong column type … expected: array, found: int2` or similar, the entity needs `@Column(columnDefinition = "...")` to disambiguate. The strict-validate stance is the whole point of the phase.

---

## Step 3 — Repositories (empty interfaces)

No custom queries this phase. Phase 3 adds `findOverlapping` / `existsOverlapping` when their consumer (`AvailabilityService`, `ScheduledEventService`) arrives.

```
domain/repo/EventTypeRepository       extends JpaRepository<EventTypeEntity, String>      {}
domain/repo/ScheduledEventRepository  extends JpaRepository<ScheduledEventEntity, String> {}
domain/repo/CalendarConfigRepository  extends JpaRepository<CalendarConfigEntity, Short>  {}
```

Spring Boot auto-discovers `JpaRepository` interfaces under the `@SpringBootApplication` package — no `@EnableJpaRepositories` needed.

---

## Step 4 — `IdGenerator`

```
@Component
public class IdGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

    public String forEventType(String name)   { return "et_" + slugify(name) + "_" + suffix(6); }
    public String forScheduledEvent()         { return "se_" + suffix(22); }   // unused in Phase 2; kept here so the convention has one home
}
```

`slugify`:
1. Lower-case.
2. Replace `[^a-z0-9]+` with `-`.
3. Trim leading/trailing `-`.
4. Cap at 32 chars.
5. If the result is empty, use `evt`.

`suffix(n)`: `n` characters drawn from `ALPHABET` via `SecureRandom`.

No tests yet — Phase 3's `EventTypeService` test covers it incidentally; for now an ad-hoc curl POST proves the format matches `^et_[a-z0-9-]+_[a-z0-9]{6}$`.

---

## Step 5 — `EventTypeMapper` (the only mapper this phase)

```
@Mapper(componentModel = "spring")
public interface EventTypeMapper {
    @Mapping(source = "id",   target = "eventTypeId")
    @Mapping(source = "name", target = "eventTypeName")
    EventType toDto(EventTypeEntity entity);

    List<EventType> toDtoList(List<EventTypeEntity> entities);

    @Mapping(target = "id",        ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(source = "eventTypeName", target = "name")
    EventTypeEntity toEntity(CreateEventType dto);
}
```

`componentModel = "spring"` — the generated `EventTypeMapperImpl` is a `@Component` that constructor-injects into `EventTypeService`.

`./gradlew compileJava`, then inspect `build/generated/sources/annotationProcessor/java/main/com/hexlet/calendar/mapping/EventTypeMapperImpl.java` once to confirm the `name ↔ eventTypeName` rename is wired correctly. If the file is empty or absent, the annotation processor silently no-op'd — see Risks.

`ScheduledEventMapper` and `TimeSlotMapper` are **deferred to Phase 3.** They have no consumer in Phase 2, and `TimeSlotMapper`'s `LocalTime ↔ "HH:mm:ss"` conversion is intertwined with the slot algorithm — designing it now risks rework.

---

## Step 6 — `EventTypeService`

```
@Service
public class EventTypeService {
    private final EventTypeRepository repo;
    private final EventTypeMapper     mapper;
    private final IdGenerator         idGen;

    public EventTypeService(EventTypeRepository repo, EventTypeMapper mapper, IdGenerator idGen) {
        this.repo = repo;
        this.mapper = mapper;
        this.idGen = idGen;
    }

    @Transactional(readOnly = true)
    public List<EventType> list() {
        return mapper.toDtoList(repo.findAll(Sort.by("createdAt")));
    }

    @Transactional
    public EventType create(CreateEventType req) {
        EventTypeEntity entity = mapper.toEntity(req);
        entity.setId(idGen.forEventType(req.getEventTypeName()));
        return mapper.toDto(repo.save(entity));
    }
}
```

No service-level validation. Bean Validation on the generated `CreateEventType` (with `@NotNull` on every field) plus the controller's `@Valid @RequestBody` already enforces the contract; duplicating it here would just give two error paths for the same problem.

`Sort.by("createdAt")` makes list order deterministic — useful for the Phase 3 integration tests.

---

## Step 7 — `CalendarController` (real methods + four 501 stubs)

```
@RestController
public class CalendarController implements CalendarApi {
    private final EventTypeService eventTypeService;

    public CalendarController(EventTypeService eventTypeService) {
        this.eventTypeService = eventTypeService;
    }

    @Override
    public ResponseEntity<EventType> calendarServiceCreateEventType(CreateEventType req) {
        return ResponseEntity.ok(eventTypeService.create(req));
    }

    @Override
    public ResponseEntity<List<EventType>> calendarServiceListEventTypes() {
        return ResponseEntity.ok(eventTypeService.list());
    }

    // Phase 3+ stubs.
    @Override public ResponseEntity<ScheduledEvent>          calendarServiceCreateScheduledEvent(CreateScheduledEvent req)         { return notImplemented(); }
    @Override public ResponseEntity<ScheduledEvent>          calendarServiceGetScheduledEventById(String id)                       { return notImplemented(); }
    @Override public ResponseEntity<List<ScheduledEvent>>    calendarServiceListScheduledEvents()                                  { return notImplemented(); }
    @Override public ResponseEntity<List<TimeSlotsOfTheDay>> calendarServiceListAvailableSlots(String eventTypeId, String tz)      { return notImplemented(); }

    private static <T> ResponseEntity<T> notImplemented() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
```

The four stubs return empty-body 501s. The CLAUDE.md `Error`-shaped 501 is a nicety that needs `ApiExceptionHandler`, which is explicitly out of scope here.

The four overrides are **not optional** — `CalendarApi` declares them all, so the file won't compile without them. That's the contract enforcement working as intended.

### 7.1 Verify (one-shot)

```sh
cd backend
./gradlew clean compileJava
./gradlew bootRun &

curl -s http://localhost:8080/api/calendar/event_types
# → []

curl -s -X POST http://localhost:8080/api/calendar/event_types \
  -H 'Content-Type: application/json' \
  -d '{"eventTypeName":"Intro Call","description":"Short chat","durationMinutes":30}'
# → 200 with eventTypeId matching ^et_[a-z0-9-]+_[a-z0-9]{6}$

curl -s http://localhost:8080/api/calendar/event_types | jq .
# → [ { ...the created row... } ]

docker compose -f compose.yaml exec postgres \
  psql -U calendar -d calendar -c 'SELECT id, name, duration_minutes FROM event_types;'
# → one row, columns intact

curl -i -X POST http://localhost:8080/api/calendar/scheduled_events \
  -H 'Content-Type: application/json' -d '{}'
# → HTTP/1.1 501

curl -i -X POST http://localhost:8080/api/calendar/event_types \
  -H 'Content-Type: application/json' -d '{"description":"x","durationMinutes":30}'
# → HTTP/1.1 400 (Spring's default validation handler — Error-shape comes in Phase 3)
```

---

## File-creation order (single pass, no rework)

```
backend/src/main/java/com/hexlet/calendar/
├── domain/
│   ├── model/
│   │   ├── BreakItem.java                              # 1.1
│   │   ├── EventTypeEntity.java                        # 2.1
│   │   ├── ScheduledEventEntity.java                   # 2.2
│   │   └── CalendarConfigEntity.java                   # 2.3
│   ├── converter/
│   │   └── ZoneIdConverter.java                        # 1.2
│   └── repo/
│       ├── EventTypeRepository.java                    # 3
│       ├── ScheduledEventRepository.java               # 3
│       └── CalendarConfigRepository.java               # 3
├── service/
│   ├── IdGenerator.java                                # 4
│   └── EventTypeService.java                           # 6
├── mapping/
│   └── EventTypeMapper.java                            # 5
└── web/
    └── CalendarController.java                         # 7
```

Files NOT touched: `application.yml`, V1__init.sql, V2__seed_owner_calendar.sql, `frontend/vite.config.ts`. Phase 1 left the configuration correct; Phase 2 only adds Java.

---

## Risks to watch

- **`OffsetDateTime` ↔ `TIMESTAMPTZ` round-trips only because `hibernate.jdbc.time_zone: UTC` is set.** Treat that line in `application.yml` as load-bearing; without it, offset-bearing values silently shift by the JVM's default zone, and every Phase 3 slot test will fail in mysterious ways.
- **`SMALLINT[]` mapping pitfalls.** `Short[]` with `@JdbcTypeCode(SqlTypes.ARRAY)` works on Hibernate 6.6 / Boot 3.4, but if `validate` complains `expected: array, found: int2`, add `@Column(columnDefinition = "smallint[]")` explicitly. The seed `ARRAY[1,2,3,4,5]::SMALLINT[]` is unambiguous on the DB side; the entity side is the friction point.
- **JSONB ↔ `List<BreakItem>`.** `@JdbcTypeCode(SqlTypes.JSON)` requires Jackson on the classpath (already there via web starter) and a concrete generic type. The seed `'[]'::JSONB` is the easy case; verify a non-empty round-trip once Phase 3 seeds a real break — strings vs. `LocalTime` is the most likely surprise.
- **MapStruct silent no-op.** If `annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")` were missing from `build.gradle.kts` (it isn't — Phase 1 set it up), the interface compiles but no `*Impl` generates, and Spring fails at startup with `NoSuchBeanDefinitionException`. After Step 5, confirm `build/generated/sources/annotationProcessor/java/main/` is non-empty. In IntelliJ, enable "Annotation Processors" for the module or rely on Gradle, not IDE compilation.
- **Single `CalendarApi` trap.** Every interface method must be overridden, or the file won't compile. The four 501 stubs are load-bearing scaffolding for Phase 3 — don't comment them out, don't drop overrides, don't `@SneakyThrows` your way past missing signatures.

---

## Commit plan

One commit, since Phase 2 is one logical unit and the prior cadence (`8c28e3d`) was also a single bundled commit:

- `feat(backend): jpa entities + event-type endpoints (phase 2)`

Optional follow-up commit if CLAUDE.md updates land separately:

- `docs(backend): record phase 2 controller deviation`

---

## Hand-off to Phase 3

Phase 3 starts at §11.6 of CLAUDE.md (`SlotMath` + unit tests) and ends at §11.7 (`AvailabilityService` + `AvailableSlotsController` integration). Pre-conditions Phase 2 leaves behind:

- All three entities and repositories in place; `validate` passes.
- `CalendarConfigEntity` ready to be loaded by `AvailabilityService`.
- `EventTypeEntity` discoverable via `EventTypeRepository.findById(...)` for the booking flow.
- `IdGenerator.forScheduledEvent()` already in place for Phase 3's `ScheduledEventService`.
- `CalendarController` already implementing `CalendarApi` — Phase 3 replaces the four 501 stubs in place, no new controller class needed.
- Integration tests (`AbstractIntegrationTest`, `EventTypeControllerIT`, etc.) deferred to Phase 3 alongside the Clock and `ApiExceptionHandler` they'll naturally depend on.

---

## Deviations from CLAUDE.md applied during Phase 2

1. **Single `CalendarController` instead of three.** §3 lists `EventTypeController`, `ScheduledEventController`, `AvailableSlotsController`. The OpenAPI generator emits one `CalendarApi` because the spec has one tag, and Java only allows one class per interface. Resolved by collapsing to `web/CalendarController.java` and preserving the strategic split at the service layer. CLAUDE.md §3's project layout listing should be updated accordingly.

2. **Mappers narrowed to one.** §11.4 says "Entities, repositories, mappers" with "MapStruct mappers" plural. Only `EventTypeMapper` ships in Phase 2; `ScheduledEventMapper` and `TimeSlotMapper` are deferred to Phase 3 because they have no consumer this phase, and `TimeSlotMapper`'s string-vs-`LocalTime` conversion is best designed alongside the slot algorithm.

3. **Integration tests deferred.** §11.4–5 implies tests in this phase. Phase 2 has only two trivial endpoints, both fully exercised by the Step 7 verification curls. Building `AbstractIntegrationTest` now means rewriting it once `Clock` and `ApiExceptionHandler` exist in Phase 3 — better to invest once.

---

## Notes from implementation (post-run)

These were observed while running the verification steps and are worth carrying into Phase 3.

1. **`POST /api/calendar/scheduled_events` with `{}` returns `400`, not `501`** — contrary to the §7.1 verify block on lines 288–290. Spring's `@Valid @RequestBody CreateScheduledEvent` triggers Bean Validation *before* the controller method runs, and every field on `CreateScheduledEvent` is `@NotNull`, so an empty body fails validation and never reaches the `notImplemented()` stub. The stub is correctly wired and **does** return `501` when the body deserializes successfully (e.g. all required fields present with placeholder values) — verified by overriding behavior, not by curl-with-empty-body. The Phase 3 `ApiExceptionHandler` will reshape this `400` into the typed `Error` body. Exit criterion #7 is still satisfied: the four overrides exist, compile, and return `501` from inside the method body. Update the verify block in §7.1 to either send a fully-valid body or accept `400` as the expected status for the empty-body case.

2. **`slugify` re-trims trailing `-` after the 32-char cap.** If the substring slice lands inside a `-` cluster the result would carry a trailing dash (e.g. `"a---b---...-"` → `"a---b---...-"` truncated to 32 chars could end on `-`), which then fails the `^et_[a-z0-9-]+_[a-z0-9]{6}$` regex visually-cleanly even though it technically matches. The implementation runs `replaceAll("-+$", "")` once more after `substring(0, 32)` for cosmetic reasons. Steps 1–5 of the plan's `slugify` recipe are otherwise applied as-written.

3. **Postgres container is re-created on every `bootRun`** because `application.yml` sets `spring.docker.compose.lifecycle-management: start-and-stop`. The named volume `calendar-pgdata` declared in `compose.yaml` preserves rows across these restarts, so exit criterion #5 (persistence across `bootRun` restarts) holds in practice. Worth keeping in mind for Phase 3 integration tests, which use Testcontainers and bypass this Compose lifecycle entirely.

4. **MapStruct generation confirmed.** `build/generated/sources/annotationProcessor/java/main/com/hexlet/calendar/mapping/EventTypeMapperImpl.java` exists after `compileJava`, mapping `entity.id ↔ dto.eventTypeId` and `entity.name ↔ dto.eventTypeName` exactly as the plan specifies. The risk on lines 333–334 (silent no-op) did not materialize.

5. **Hibernate `validate` accepted `Short[]` + `@JdbcTypeCode(SqlTypes.ARRAY)` against `SMALLINT[]`** without needing `@Column(columnDefinition = "smallint[]")` for the `expected: array` error path — but `columnDefinition` is set anyway because the plan calls for it, and it costs nothing. The `JSONB` round-trip for `breaks` is empty (`'[]'`) on the seed row; non-empty round-trip remains untested until Phase 3 seeds a real break.
