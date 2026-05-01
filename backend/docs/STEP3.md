# Phase 3 — `SlotMath` + availability endpoint (Tactical Plan)

Companion to [backend/CLAUDE.md](../CLAUDE.md). Covers `§11.6` (`SlotMath` + unit tests) and `§11.7` (`AvailabilityService` + `AvailableSlotsController` integration). Phase 2 ([STEP2.md](STEP2.md)) is the precondition: entities, repositories, `EventTypeMapper`, `IdGenerator`, and a `CalendarController implements CalendarApi` with four `501` stubs are already in place.

## Context

The frontend requires a real implementation of `GET /calendar/event_types/{id}/available_slots?clientTimeZone=…` so guests can see bookable slots in a 14-day rolling window. Phase 2 wired up the persistence layer and the trivial event-type CRUD; STEP3 adds the slot-generation algorithm (the algorithmic core of the whole backend) and replaces the `501` stub. The slot algorithm must respect owner working days, business hours, breaks, existing bookings, time-zone math (including DST), and the past-slot filter — all driven by a `Clock` so tests can pin "today."

Booking creation, the typed `Error` body (`ApiExceptionHandler`), and `existsOverlapping` are explicitly deferred to Phase 4. The hand-off statement at the bottom of STEP2.md is authoritative: STEP3 ends at §11.7.

## Goal

Replace the `501` stub for `calendarServiceListAvailableSlots(...)` with a real implementation. The slot algorithm (`time/SlotMath.java`) is purely functional and exhaustively unit-tested; `AvailabilityService` wires it to the DB; `ScheduledEventRepository.findOverlapping(...)` is the only custom JPA query of this phase. Test infrastructure (`AbstractIntegrationTest` + a test-only `Clock` bean) lands here so Phase 4 can reuse it.

## Exit criteria (all must hold)

1. `./gradlew clean build` — `BUILD SUCCESSFUL`. Annotation processor regenerates DTOs; MapStruct still emits `EventTypeMapperImpl`. Unit + integration tests all green; Testcontainers spins up `postgres:16-alpine` for ITs.
2. `SlotMathTest` has ≥12 distinct `@Test` methods covering every edge case in §4 of CLAUDE.md (typical Mon–Fri, weekend filter, break filter for both 30- and 60-min events, existing-booking filter, past-slot filter, window endpoints, owner/client zone date boundary in both directions, DST spring-forward, DST fall-back, empty result).
3. `AvailabilityServiceTest` has ≥3 `@Test` methods (happy path, missing event type → `ResponseStatusException(NOT_FOUND)`, invalid timezone → `ResponseStatusException(BAD_REQUEST)`).
4. `AvailableSlotsControllerIT` has ≥5 `@Test` methods (happy path, existing booking removes a slot, invalid `clientTimeZone` → 400, missing event type → 404, weekend has no slots).
5. After `./gradlew bootRun`:
   ```
   curl -i --get http://localhost:8080/api/calendar/event_types/{id}/available_slots \
     --data-urlencode "clientTimeZone=Europe/Berlin"
   ```
   returns `200`. Body is a JSON array of up to 14 elements (working days only), each `{ date: "YYYY-MM-DD", timeSlots: [{ timeStart: "HH:mm:ss", duration: 30 }, …] }`.
6. Inserting one row into `scheduled_events` covering 10:00–10:30 Berlin local on a working day inside the 14-day window: that slot is **absent** from a re-list.
7. `clientTimeZone=Mars/Phobos` → `400`; unknown `eventTypeId` → `404`. Body shape is Spring's default error JSON (typed `Error` arrives in Phase 4).
8. The other three `CalendarApi` stubs (`createScheduledEvent`, `getScheduledEventById`, `listScheduledEvents`) still return `501` unchanged.
9. `application.yml`, V1__init.sql, V2__seed_owner_calendar.sql, `frontend/`, and `build.gradle.kts` are unchanged.

---

## Architecture decisions

### A. SlotMath returns `List<Instant>`, not pre-grouped

`SlotMath.generate(...)` returns a flat, time-sorted `List<Instant>` of UTC slot starts. Grouping by client-local date and the `Instant → "HH:mm:ss"` rendering happen in `AvailabilityService`. Rationale: `SlotMath` knows nothing about the wire DTO. The grouping is one `groupingBy` call against a `Clock`+`ZoneId` — no algorithmic content to test in isolation.

### B. No MapStruct mapper this phase

`Instant → ZonedDateTime → LocalTime → "HH:mm:ss"` depends on a runtime `ZoneId` (the request's `clientTimeZone`). MapStruct would need `@Context` and qualified mappings — more wiring than the six-line stream in `AvailabilityService`. STEP2.md already flagged `TimeSlotMapper` as deferrable; STEP3 drops it entirely.

### C. SlotMath defines its own value types

Nested records inside `SlotMath`:
- `BreakWindow(LocalTime start, LocalTime endExclusive)`
- `UtcRange(Instant start, Instant endExclusive)`

`SlotMath` does **not** depend on `BreakItem` or `ScheduledEventEntity`. `AvailabilityService` translates at the boundary. Pure-vs-orchestration boundary — the `time/` package has no dependency on `domain/`.

### D. Error signaling — `ResponseStatusException`, no custom exceptions

`ApiExceptionHandler` and the typed `Error` body arrive in Phase 4. STEP3 uses `ResponseStatusException(BAD_REQUEST)` for invalid timezone and `ResponseStatusException(NOT_FOUND)` for unknown event type. Spring honors the status code in its default error path — Phase 4 replaces these with typed exceptions and the handler reshapes the body, no service-layer changes needed.

### E. `Short[] workingDays` → `Set<DayOfWeek>` in the service, not in SlotMath

```java
Set<DayOfWeek> workingDays = Arrays.stream(config.getWorkingDays())
    .map(s -> DayOfWeek.of(s.intValue()))
    .collect(Collectors.toUnmodifiableSet());
```

`DayOfWeek.of(int)` already maps `1=Mon..7=Sun`, matching the V2 seed convention.

### F. Per-test cleanup — `@Transactional` rollback on the test method

Every IT method is `@Transactional`. Spring rolls back at end of test, including any `INSERT` into `scheduled_events`. The V2-seeded `calendar_config` row survives (we never write to it). `MockMvc` is single-threaded, so write-visibility-to-controller works inside the same test transaction.

### G. Test infrastructure — Testcontainers `@ServiceConnection`, fixed `Clock`

`AbstractIntegrationTest` uses `@SpringBootTest(WebEnvironment.MOCK)` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")` + `@Testcontainers` with a `static @Container @ServiceConnection PostgreSQLContainer<?>("postgres:16-alpine")`. The static field lets the container survive across IT classes (Spring's context cache + `@ServiceConnection` reuse it). `application-test.yml` overrides `spring.docker.compose.enabled: false`. Flyway runs naturally — V2 seed is present in every IT.

`FixedClockTestConfig` provides `Clock.fixed(Instant.parse("2026-05-04T05:00:00Z"), ZoneOffset.UTC)` (07:00 Berlin Mon — well before 09:00 first slot, working day). `@Primary` overrides production `ClockConfig`. `FIXED_NOW` is a `public static final` so tests compute expected dates from it.

### H. `MockMvc` does not honor `server.servlet.context-path`

Easy mistake: the curl uses `/api/calendar/...`, but `MockMvc` requests `/calendar/...` (relative to dispatcher servlet). Exit Criterion 5 verifies the `/api` prefix via curl; ITs verify the controller mapping.

---

## File-creation order (single pass, no rework)

```
backend/src/main/java/com/hexlet/calendar/
├── config/
│   └── ClockConfig.java                                # step 1
├── time/
│   └── SlotMath.java                                   # step 2 (with nested BreakWindow, UtcRange records)
├── domain/repo/
│   └── ScheduledEventRepository.java                   # step 3 — modify (add findOverlapping)
├── service/
│   └── AvailabilityService.java                        # step 4
└── web/
    └── CalendarController.java                         # step 5 — modify (replace one stub + inject new dep)

backend/src/test/java/com/hexlet/calendar/
├── time/
│   └── SlotMathTest.java                               # step 6
├── service/
│   └── AvailabilityServiceTest.java                    # step 7
└── web/
    ├── FixedClockTestConfig.java                       # step 8
    ├── AbstractIntegrationTest.java                    # step 8
    └── AvailableSlotsControllerIT.java                 # step 9

backend/src/test/resources/
└── application-test.yml                                # step 8
```

Files **not** touched: `application.yml`, V1__init.sql, V2__seed_owner_calendar.sql, `build.gradle.kts`, all entities, `BreakItem`, `ZoneIdConverter`, `EventTypeService`, `EventTypeMapper`, `IdGenerator`, `frontend/`.

---

## Step 1 — `config/ClockConfig.java`

```java
@Configuration
public class ClockConfig {
    @Bean
    public Clock clock() { return Clock.systemUTC(); }
}
```

Tests override via `@TestConfiguration` + `@Primary`.

---

## Step 2 — `time/SlotMath.java`

Pure final class with private constructor. Signature:

```java
public static List<Instant> generate(
    int durationMinutes,
    ZoneId ownerZone,
    LocalTime startOfDay,
    LocalTime endOfDay,
    Set<DayOfWeek> workingDays,
    List<BreakWindow> breaks,
    List<UtcRange> existingBookings,
    ZoneId clientZone,
    Clock clock
);

public record BreakWindow(LocalTime start, LocalTime endExclusive) {}
public record UtcRange(Instant start, Instant endExclusive) {}
```

Algorithm:

1. `clientToday = LocalDate.now(clock.withZone(clientZone))`
2. `windowStart = clientToday.atStartOfDay(clientZone).toInstant()`; `windowEnd = clientToday.plusDays(14).atStartOfDay(clientZone).toInstant()` (exclusive)
3. `nowUtc = Instant.now(clock)`
4. Sweep `[clientToday.minusDays(1), clientToday.plusDays(15)]` owner-locally to absorb zone gaps; the post-window filter tightens.
5. Skip days whose `DayOfWeek` not in `workingDays`.
6. Step from `startOfDay` by `durationMinutes` while `slotEnd ≤ endOfDay` (strict `>` rejects, so 09:00–17:00 with 30-min event gives last slot 16:30–17:00 — matches spec example).
7. Reject slots overlapping any break: `t.isBefore(b.endExclusive()) && b.start().isBefore(slotEnd)` (half-open both sides — slot ending exactly at break start is kept; same convention as V1's EXCLUDE GIST).
8. `slotInstant = ZonedDateTime.of(d, t, ownerZone).toInstant()` — DST handled natively (gap shifts forward; ambiguous picks earlier offset).
9. Drop if `slotInstantEnd > windowEnd`, `slotInstant < windowStart`, or `slotInstant < nowUtc`.
10. Reject slots overlapping existing bookings (linear scan, half-open).
11. Sort; return immutable copy.

---

## Step 3 — `domain/repo/ScheduledEventRepository.java` (modify, add one query)

```java
@Query(value = """
    SELECT * FROM scheduled_events
    WHERE tstzrange(utc_start, utc_end, '[)') && tstzrange(:windowStart, :windowEnd, '[)')
    """, nativeQuery = true)
List<ScheduledEventEntity> findOverlapping(
    @Param("windowStart") Instant windowStart,
    @Param("windowEnd")   Instant windowEnd
);
```

Native because JPQL has no `tstzrange` literal. Same range expression as V1's EXCLUDE constraint — query and constraint stay in lockstep. `Instant ↔ TIMESTAMPTZ` round-trips are clean only because `application.yml` sets `hibernate.jdbc.time_zone: UTC` (load-bearing).

`existsOverlapping` is **deferred** to Phase 4.

---

## Step 4 — `service/AvailabilityService.java`

Class skeleton (constructor-injects `EventTypeRepository`, `CalendarConfigRepository`, `ScheduledEventRepository`, `Clock`):

```java
@Transactional(readOnly = true)
public List<TimeSlotsOfTheDay> listAvailableSlots(String eventTypeId, String clientTimeZoneRaw) {
    ZoneId clientZone;
    try { clientZone = ZoneId.of(clientTimeZoneRaw); }
    catch (DateTimeException ex) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Invalid timezone: " + clientTimeZoneRaw);
    }

    EventTypeEntity eventType = eventTypeRepo.findById(eventTypeId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Event type not found: " + eventTypeId));

    CalendarConfigEntity config = calendarConfigRepo.findById((short) 1)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "Owner calendar not configured"));

    LocalDate clientToday = LocalDate.now(clock.withZone(clientZone));
    Instant windowStart = clientToday.atStartOfDay(clientZone).toInstant();
    Instant windowEnd   = clientToday.plusDays(14).atStartOfDay(clientZone).toInstant();

    Set<DayOfWeek> workingDays = Arrays.stream(config.getWorkingDays())
        .map(s -> DayOfWeek.of(s.intValue()))
        .collect(Collectors.toUnmodifiableSet());

    List<BreakWindow> breaks = (config.getBreaks() == null ? List.<BreakItem>of() : config.getBreaks())
        .stream()
        .map(bi -> new BreakWindow(bi.getTimeStart(),
                                   bi.getTimeStart().plusMinutes(bi.getDuration())))
        .toList();

    List<UtcRange> existingBookings = scheduledEventRepo
        .findOverlapping(windowStart, windowEnd).stream()
        .map(se -> new UtcRange(se.getUtcStart().toInstant(),
                                se.getUtcEnd().toInstant()))
        .toList();

    List<Instant> slots = SlotMath.generate(
        eventType.getDurationMinutes(), config.getOwnerTimezone(),
        config.getStartOfDay(), config.getEndOfDay(),
        workingDays, breaks, existingBookings, clientZone, clock);

    DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss");
    Integer dur = eventType.getDurationMinutes();
    return slots.stream()
        .collect(Collectors.groupingBy(
            i -> i.atZone(clientZone).toLocalDate(), TreeMap::new, Collectors.toList()))
        .entrySet().stream()
        .map(e -> new TimeSlotsOfTheDay(e.getKey(),
            e.getValue().stream().sorted()
                .map(i -> new TimeSlot(HHMMSS.format(i.atZone(clientZone).toLocalTime()), dur))
                .toList()))
        .toList();
}
```

`@Transactional` is method-level (not class) so Phase 4's writable methods can live alongside.

---

## Step 5 — `web/CalendarController.java` (modify)

Add `AvailabilityService` field + constructor param. Replace **only** the body of `calendarServiceListAvailableSlots(...)`:

```java
@Override
public ResponseEntity<List<TimeSlotsOfTheDay>> calendarServiceListAvailableSlots(
        String eventTypeId, String clientTimeZone) {
    return ResponseEntity.ok(availabilityService.listAvailableSlots(eventTypeId, clientTimeZone));
}
```

The other three 501 stubs are untouched.

---

## Step 6 — `time/SlotMathTest.java`

Pure JUnit 5; no Spring. Helpers:

```java
private static Clock fixedAt(String iso) {
    return Clock.fixed(Instant.parse(iso), ZoneOffset.UTC);
}
private static final ZoneId BERLIN   = ZoneId.of("Europe/Berlin");
private static final ZoneId HONOLULU = ZoneId.of("Pacific/Honolulu");
private static final ZoneId AUCKLAND = ZoneId.of("Pacific/Auckland");
private static final Set<DayOfWeek> MON_FRI = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
```

Tests (one `@Test` each):

1. **`typicalMonFri30MinYields16SlotsPerWorkingDay`** — clock `2026-05-04T05:00:00Z` (07:00 Berlin Mon). 30-min, 09:00–17:00, no breaks, no bookings. Expect 10 working days × 16 slots = 160 instants; first `2026-05-04T07:00:00Z` (09:00 Berlin Mon).
2. **`weekendsAreSkipped`** — same setup; assert no instant maps to `2026-05-09` or `2026-05-10` Berlin-local.
3. **`break_30MinEvent`** — break 12:00–13:00, 30-min event. Expect 12:00 and 12:30 candidates absent → 14 slots/day.
4. **`break_60MinEvent_halfOpenBoundary`** — break 12:00–13:00, 60-min event, step 60 from 09:00 (09:00, 10:00, 11:00, 12:00, …). Slot 11:00–12:00 **kept** (ends exactly at break start; half-open both ways); slot 12:00–13:00 **dropped**. Pin the exact list.
5. **`existingBookingRemovesExactSlot`** — 30-min event, booking `2026-05-04T08:00:00Z..2026-05-04T08:30:00Z` (10:00–10:30 Berlin Mon). Assert that instant absent; 09:30 and 10:30 present.
6. **`existing30MinBookingBlocks60MinEvent`** — 60-min event, booking 10:00–10:30 Berlin Mon. Slot 10:00–11:00 absent (overlaps). Slot 09:00–10:00 **kept** (ends exactly at booking start, half-open).
7. **`pastSlotsFiltered_today`** — clock `2026-05-04T08:30:00Z` (10:30 Berlin Mon). Assert 09:00 and 09:30 absent; **10:30 present** (`< nowUtc` is strict).
8. **`windowEndpoints_todayIncluded_today14Excluded`** — clock at start of client-local today; 30-min event. Assert ≥1 slot has client-local date == today; no slot has client-local date == today+14.
9. **`berlinTuesday09GroupsToHonoluluMonday21`** — owner Berlin Tue 09:00 = `2026-05-05T07:00:00Z` = Mon 21:00 Honolulu (UTC-10). Assert that instant `i.atZone(HONOLULU).toLocalDate() == LocalDate.parse("2026-05-04")`.
10. **`berlinFri16GroupsToAucklandSat02`** — symmetric: Berlin 16:00 Fri → 14:00Z → 02:00 Sat Auckland.
11. **`dstSpringForward_noDuplicateInstants`** — Berlin 2026-03-29 (Sun, force `workingDays = {SUNDAY}`); `startOfDay = 01:30`, `endOfDay = 04:00`, 30-min event. Assert: returned set has no duplicates AND the slot at owner-local 02:00 maps to the same `Instant` as owner-local 03:00 (gap shifted forward; the algorithm produces both as candidates but `ZonedDateTime.of` collapses them — handle by deduping in the result OR by accepting the documented behavior; pin whichever the implementation does, this test exists to catch silent regressions).
12. **`dstFallBack_ambiguousPicksEarlierOffset`** — Berlin 2026-10-25 (Sun, force `{SUNDAY}`); `startOfDay = 02:00`, `endOfDay = 03:00`, 60-min event. Slot 02:00–03:00 is ambiguous (occurs twice). `ZonedDateTime.of` resolves to UTC+2 (earlier offset) → `2026-10-25T00:00:00Z`. Assert exact equality.
13. **`emptyResultWhenNoWorkingDays`** — `workingDays = EnumSet.noneOf(DayOfWeek.class)` → `[]`.

---

## Step 7 — `service/AvailabilityServiceTest.java`

JUnit + Mockito (transitive via `spring-boot-starter-test`). No Spring context. Mocks all three repos; constructs `AvailabilityService` with a `Clock.fixed`. Three tests:

1. **`happyPath_returnsExpectedShape`** — stubs all repos; assert returned list has expected working-day count; first day's first `timeStart == "09:00:00"`. Verify `findOverlapping` was called with the correct UTC window via `verify(...).findOverlapping(eq(expectedStart), eq(expectedEnd))`.
2. **`unknownEventType_throws404`** — `findById` empty; assert `ResponseStatusException`, status `NOT_FOUND`.
3. **`invalidClientTimeZone_throws400`** — pass `"Mars/Phobos"`; assert `ResponseStatusException`, status `BAD_REQUEST`. Verify repos were **not** called (timezone check runs first, before `eventTypeRepo.findById`).

---

## Step 8 — Test infrastructure

### `src/test/resources/application-test.yml`
```yaml
spring:
  docker:
    compose:
      enabled: false
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.jdbc.time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration
```

### `web/FixedClockTestConfig.java`
```java
@TestConfiguration
public class FixedClockTestConfig {
    public static final Instant FIXED_NOW = Instant.parse("2026-05-04T05:00:00Z"); // 07:00 Berlin Mon

    @Bean @Primary
    public Clock clock() { return Clock.fixed(FIXED_NOW, ZoneOffset.UTC); }
}
```

### `web/AbstractIntegrationTest.java`
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(FixedClockTestConfig.class)
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired protected MockMvc                   mockMvc;
    @Autowired protected ObjectMapper              objectMapper;
    @Autowired protected EventTypeRepository       eventTypeRepo;
    @Autowired protected ScheduledEventRepository  scheduledEventRepo;
    @Autowired protected IdGenerator               idGen;
}
```

Seeding strategy in ITs:
- `calendar_config` row 1 — already seeded by V2 (Mon–Fri 09–17 Berlin, no breaks).
- `event_types` — created per-test via `eventTypeRepo.save(new EventTypeEntity(...))` with hand-fixed id (e.g. `"et_test_intro_aaaaaa"`) for assertion stability.
- `scheduled_events` — saved per-test; must reference an `event_type_id` that exists (FK enforced at commit but visible inside the same `@Transactional`).

---

## Step 9 — `web/AvailableSlotsControllerIT.java`

Extends `AbstractIntegrationTest`. `@Transactional` on the class.

Tests:

1. **`happyPath_returns14DayWindowOfSlots`** — save event type (`durationMinutes=30`); `mockMvc.get("/calendar/event_types/{id}/available_slots?clientTimeZone=Europe/Berlin")`; assert 200; deserialize body to `List<TimeSlotsOfTheDay>`; assert size 8–10 (working days in 14-day Mon-anchored window from `FIXED_NOW`); first day's first `timeStart == "09:00:00"`.
2. **`existingBookingExcludesItsSlot`** — save event type; save scheduled event covering 10:00–10:30 Berlin on `2026-05-04`; assert `2026-05-04` `timeSlots` contains `"09:30:00"` and `"10:30:00"` but **not** `"10:00:00"`.
3. **`invalidClientTimeZone_returns400`** — `?clientTimeZone=Mars/Phobos` → `status().isBadRequest()`.
4. **`unknownEventType_returns404`** — id `et_does_not_exist` → `status().isNotFound()`.
5. **`weekendsHaveNoSlots`** — `2026-05-09` (Sat) and `2026-05-10` (Sun) absent from response dates.

`MockMvc` request path is `/calendar/...` (no `/api` prefix — see decision §H).

---

## Risks to watch

- **`hibernate.jdbc.time_zone: UTC` is load-bearing for `findOverlapping`.** Without it, the native query's `Instant` parameter binding silently uses the JVM's default zone and the overlap window is wrong by hours. STEP2.md flagged this for entity round-trips; it applies just as strongly to native queries.
- **Half-open vs closed range for break/booking overlap.** A slot of 11:00–12:00 with a break of 12:00–13:00 must be **kept** (slot ends exactly at break start; both sides exclusive). Tests 4 and 6 of `SlotMathTest` pin this. If the algorithm uses `<=`/`>=` somewhere, those tests fail.
- **`ZonedDateTime.of` DST resolution is documented but counterintuitive.** Spring-forward gap shifts forward; fall-back ambiguous picks the **earlier** offset. Tests 11 and 12 pin this. The Berlin owner under V2 seed (09–17 Mon–Fri) never naturally hits DST, so a regression could ship unnoticed without those tests.
- **Owner local sweep range vs window filter.** The sweep `[clientToday-1, clientToday+15]` overshoots; the post-window filter tightens it. Anyone "optimizing" the sweep to `[clientToday, clientToday+13]` makes slots near zone boundaries quietly disappear.
- **Testcontainers cold start (~5–10s on a clean machine).** First IT run pulls `postgres:16-alpine`. Subsequent runs reuse via Spring's context cache + the static `@Container` field.
- **Flyway must run for ITs.** `application-test.yml` keeps `spring.flyway.enabled: true`. If a future profile flips it to `false`, V2 seed disappears and every IT fails with "Owner calendar not configured."
- **`MockMvc` does not honor `server.servlet.context-path`.** Use `/calendar/...` not `/api/calendar/...` in IT requests.
- **`ResponseStatusException` body shape vs typed `Error`.** STEP3 ITs assert on **status only**, never on body shape. Phase 4's `ApiExceptionHandler` will reshape the body — over-asserting now would force test churn later.
- **JSONB break round-trip with non-empty data is still untested.** STEP2 flagged this; V2 seeds `'[]'`. `SlotMathTest` covers the algorithm with breaks, but the persistence path with `[{12:00,60}]` first round-trips when Phase 4 (or a later seed) populates it.

---

## Verify (after `./gradlew bootRun`)

```sh
# 1. Create one event type
ET_ID=$(curl -s -X POST http://localhost:8080/api/calendar/event_types \
  -H 'Content-Type: application/json' \
  -d '{"eventTypeName":"Intro Call","description":"Short chat","durationMinutes":30}' \
  | jq -r .eventTypeId)
echo "$ET_ID"

# 2. Happy path — Berlin
curl -i --get "http://localhost:8080/api/calendar/event_types/$ET_ID/available_slots" \
  --data-urlencode "clientTimeZone=Europe/Berlin" | jq .

# 3. Different client zone — same backing data, different grouping
curl -s --get "http://localhost:8080/api/calendar/event_types/$ET_ID/available_slots" \
  --data-urlencode "clientTimeZone=Pacific/Honolulu" | jq '.[0]'

# 4. Invalid timezone → 400
curl -i --get "http://localhost:8080/api/calendar/event_types/$ET_ID/available_slots" \
  --data-urlencode "clientTimeZone=Mars/Phobos"

# 5. Missing event type → 404
curl -i --get "http://localhost:8080/api/calendar/event_types/et_does_not_exist/available_slots" \
  --data-urlencode "clientTimeZone=Europe/Berlin"

# 6. Insert one booking via psql, re-list, see slot disappear
docker compose -f compose.yaml exec postgres \
  psql -U calendar -d calendar -c "
    INSERT INTO scheduled_events
      (id, event_type_id, utc_start, utc_end, duration_minutes,
       subject, notes, guest_name, guest_email, guest_timezone)
    VALUES
      ('se_manual_test', '$ET_ID',
       '2026-05-04 08:00:00+00', '2026-05-04 08:30:00+00', 30,
       's','n','g','g@e.com','Europe/Berlin');"

curl -s --get "http://localhost:8080/api/calendar/event_types/$ET_ID/available_slots" \
  --data-urlencode "clientTimeZone=Europe/Berlin" \
  | jq '.[] | select(.date == "2026-05-04") | .timeSlots[].timeStart'
# 10:00:00 must NOT appear in this list
```

Today's actual local date is `2026-05-01` (Friday); production uses `Clock.systemUTC()` so the response reflects real time, with the window `[2026-05-01, 2026-05-15)`. The fixed-clock ITs use `2026-05-04` (Mon) for stable assertions.

---

## Hand-off to Phase 4

Phase 4 picks up CLAUDE.md §11.8 (`ScheduledEventService` + booking endpoints) and §11.9 (`ApiExceptionHandler` + typed `Error` body). Pre-conditions Phase 3 leaves behind:

- `time/SlotMath` is the single source of slot truth — Phase 4's booking pre-check calls `AvailabilityService.listAvailableSlots(...)` to verify alignment.
- `ScheduledEventRepository.findOverlapping` exists; Phase 4 adds `existsOverlapping(Instant, Instant)` for the 409 pre-check.
- `AbstractIntegrationTest` and `FixedClockTestConfig` are reusable — Phase 4's `ScheduledEventControllerIT`, `ConcurrencyIT`, `ErrorMappingIT` extend the same base.
- `application-test.yml` is in place with `docker.compose.enabled: false`.
- `Clock` bean exists; Phase 4 doesn't add one.
- `CalendarController` already has three remaining 501 stubs; Phase 4 replaces all three in place, no new controller class.
- `ResponseStatusException` usages in `AvailabilityService` are left as-is in Phase 4 — `ApiExceptionHandler` reshapes them into the typed `Error` body via Spring's standard `ErrorResponse` machinery.
- `ScheduledEventMapper` (entity ↔ DTO) is added in Phase 4 alongside the booking flow. `TimeSlotMapper` is dropped (see decision §B).

---

## Commit plan

One commit, matching prior phases' single-bundle cadence:

- `feat(backend): availability slot endpoint + slot algorithm (phase 3)`

---

## Deviations from CLAUDE.md applied during Phase 3

1. **`TimeSlotMapper` dropped.** §3 lists it; STEP2 deferred it; STEP3 deletes it. The conversion is a six-line stream in `AvailabilityService` and depends on a runtime `ZoneId` — MapStruct contributes only ceremony.
2. **No custom exception types.** §6 lists `NotFoundException`, `BadRequestException`. Phase 3 uses `ResponseStatusException` and defers the typed hierarchy to Phase 4 along with the handler that gives them their value.
3. **`SlotMath` returns `List<Instant>`, not pre-grouped.** §4 step 10 says "group by client-local date"; STEP3 moves that into `AvailabilityService` so `SlotMath` doesn't need a `ZoneId`-aware grouping operator. Behavior on the wire is identical.
4. **`SlotMath` defines its own `BreakWindow` and `UtcRange` records** rather than consuming `BreakItem` and `ScheduledEventEntity`. The `time/` package has no dependency on `domain/`.
