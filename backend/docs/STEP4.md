# Phase 4 — Booking lifecycle + typed `Error` body (Tactical Plan)

Companion to [backend/CLAUDE.md](../CLAUDE.md). Covers `§11.8` (`ScheduledEventService` + booking endpoints) and `§11.9` (`ApiExceptionHandler` + typed `Error` body). Phase 3 ([STEP3.md](STEP3.md)) is the precondition. The lower-priority polish items called out in [REVIEW3.md](REVIEW3.md) — `EventTypeControllerIT`, `hasSizeBetween → hasSize` tightening, DST test hardening — are explicitly **deferred** to a future Phase 5 cleanup commit. Phase 4 stays focused on the booking lifecycle, the typed error body, and the tests that directly prove them.

## Context

Phase 3 shipped the slot generator and `GET /available_slots`, but `CalendarController.java:39-51` still returns `501 Not Implemented` for the three `scheduled_events` endpoints — the heart of the product (`task.txt:6` "View upcoming appointments"; `task.txt:11` "Creates a booking"). Errors today come back as Spring's default `{ timestamp, status, error, path }` JSON, **not** the contract's `Error { code, message }` shape (`openapi.yaml:322`), so the API silently violates its own contract on every non-200. The hardest invariant in the spec — "no two bookings share a time, even across event types" (`task.txt:14`) — has zero tests, even though the DB-level guard already exists.

Phase 4 closes all three: implement the booking lifecycle end-to-end, give `@RestControllerAdvice` ownership of the response body for every error path, and prove the EXCLUDE-GIST constraint races correctly with a real concurrency test.

## Goal

Replace the three `notImplemented()` stubs in `CalendarController` with real implementations backed by a new `ScheduledEventService` that runs the validation pipeline from CLAUDE.md §5 (timezone parse → window → past → slot-alignment → collision pre-check → save → translate `23P01` to 409). Land an `ApiExceptionHandler` that funnels every error path — including the `ResponseStatusException`s left behind by Phase 3 — through the generated `Error` DTO. Add the three new test classes that directly verify the booking flow and the typed body: `ScheduledEventControllerIT`, `ConcurrencyIT`, `ErrorMappingIT`.

## Exit criteria (all must hold)

1. `./gradlew clean build` — `BUILD SUCCESSFUL`. All unit + integration tests green; Testcontainers spins up `postgres:16-alpine` for the IT phase. Total test count grows by ≥20 methods over Phase 3's 21.
2. Every `CalendarApi` method on `CalendarController` has a real body. No `notImplemented()` helper remains; the helper itself is deleted.
3. `POST /api/calendar/scheduled_events` happy path returns `200` with `scheduledEventId` matching `^se_[a-z0-9]{22}$` and `utcDateStart` rendered as ISO-8601 with a literal `Z` (UTC). Body of the persisted entity round-trips through `GET /api/calendar/scheduled_events/{id}` and appears in `GET /api/calendar/scheduled_events`.
4. **Cross-event-type collision**: book event type A at 10:00 Berlin Mon, then book event type B at the same time → second request returns `409` with body exactly `{"code":409,"message":"Slot is no longer available"}`. The two events have different `eventTypeId`s — the EXCLUDE constraint fires regardless.
5. **Concurrency**: `ConcurrencyIT` races two `ScheduledEventService.create(...)` calls against the same slot from two threads via `CountDownLatch`. Exactly one succeeds; the other receives a `DataIntegrityViolationException` whose root cause carries Postgres SQLSTATE `23P01`, which the service translates to `ConflictException` → 409. Both invariants asserted.
6. Every error response — `400`, `404`, `409`, `500`, malformed JSON, missing required field — emits the typed `Error` shape `{ "code": <int>, "message": <string> }`. `ErrorMappingIT` asserts the **body**, not just the status, including on the Phase 3 paths (`available_slots` 400/404) that step 3 migrates from `ResponseStatusException` to typed exceptions.
7. `application.yml`, V1__init.sql, V2__seed_owner_calendar.sql, `frontend/`, and `build.gradle.kts` are unchanged.
8. The three Phase 3 status-only assertions (`AvailableSlotsControllerIT.unknownEventType_returns404`, `…_invalidClientTimeZone_returns400`, `happyPath`'s `hasSizeBetween`) are **untouched** in this phase — `ErrorMappingIT` covers the typed-body shape on those paths via separate tests; the polish on the existing tests rolls up into Phase 5.

---

## Architecture decisions

### A. Typed exceptions replace `ResponseStatusException` everywhere (incl. Phase 3 paths)

CLAUDE.md §6 lists `NotFoundException`, `BadRequestException`, `ConflictException` extending a common `ApiException(int code, String message)`. `AvailabilityService` currently throws `ResponseStatusException` (Phase 3 deviation §D). Two reasons to migrate now rather than later:

1. **Single error-shape source.** With both code paths funneling through `ApiExceptionHandler`, the `Error` body is generated in exactly one place. If we keep `ResponseStatusException` alongside `ApiException`, the handler needs two branches and the test surface doubles.
2. **`ResponseStatusException` body shape is half-typed.** Spring 6 renders it via `ProblemDetail` (RFC 7807) by default, not the spec's `{ code, message }`. Reshaping it would require a second `@ExceptionHandler` for the same goal.

Migration is mechanical — three throw sites in `AvailabilityService`. No service-layer logic changes.

### B. `ApiExceptionHandler` returns the **generated** `Error` DTO

`com.hexlet.calendar.generated.model.Error` is the spec-derived type — using it everywhere catches drift the same way the controller interface does. Concretely:

```java
private ResponseEntity<Error> respond(int code, String message) {
    Error body = new Error();
    body.setCode(code);
    body.setMessage(message);
    return ResponseEntity.status(code).body(body);
}
```

The generator's `Error` may shadow `java.lang.Error`. Always reference it through its FQN (`com.hexlet.calendar.generated.model.Error`) inside the handler, or alias it via an `import … as` pattern (Java has no alias — use the FQN). One-line decision; flagged here to prevent the obvious mistake.

### C. Slot-alignment via `AvailabilityService.listAvailableSlots`, not a re-derived check

CLAUDE.md §5 step 8 says "validate slot alignment by calling `AvailabilityService.listAvailableSlots(...)` and asserting the requested `(date, time)` is among the returned slots." This single call enforces working day, business hours, breaks, step alignment, **and** the 14-day window in one place — exactly the surface that `SlotMathTest`'s 13 unit tests already pin. Re-deriving any of these in `ScheduledEventService` would create a second source of truth and a second place to drift.

The booking request carries `(date, time, guestTimezone)`. `listAvailableSlots(eventTypeId, guestTimezone)` returns days and `HH:mm:ss` strings **in the guest's zone** — a direct match. Lookup is `O(14)` outer × `O(slotsPerDay)` inner, both bounded; no perf concern.

Edge: `listAvailableSlots` also filters out slots already booked, so if someone tries to book an already-booked slot, the alignment check rejects with 400 ("Slot is not available"). The dedicated 409 path then only fires for **races** between two slots that were both available at read time — the rare-but-real case that the EXCLUDE constraint exists to catch. This is correct: a 409 means "you raced and lost"; a 400 means "you asked for something that was never on offer."

### D. `existsOverlapping` *and* the EXCLUDE constraint — pre-check is a UX nicety

The pre-check (`existsOverlapping`) gives a clean 409 in the common single-writer case. The EXCLUDE constraint fires for the rare race; we translate it. Both layers are needed: drop the pre-check and 99% of conflict responses arrive as 500-class `DataIntegrityViolationException` until the handler maps them; drop the constraint and concurrent writers can both commit.

The pre-check runs inside the same `@Transactional` as the save. With `READ_COMMITTED` (Spring's default), the pre-check sees only committed rows — so two concurrent transactions can both pass it. That's expected. The EXCLUDE constraint is the actual serializer.

### E. Concurrency test bypasses MockMvc

`MockMvc` is single-threaded; `@Transactional` test methods roll back, so two parallel transactions aren't even possible at the test framework layer. Options considered:

- **`@SpringBootTest(WebEnvironment.RANDOM_PORT)` + `TestRestTemplate` + `ExecutorService`.** Realistic but slow (full HTTP stack twice) and requires explicit `@AfterEach` cleanup since tests can't be `@Transactional`.
- **Direct service invocation from two threads.** Simpler — exercises the exact layer where the constraint lives. Cleanup is one `scheduledEventRepo.deleteAll()` in `@AfterEach`. The HTTP-mapping test already lives in `ScheduledEventControllerIT.crossEventTypeCollision_returns409` (single-threaded, MockMvc).

Picked direct service invocation. Decision: `ConcurrencyIT extends AbstractIntegrationTest` but **does not** mark methods `@Transactional` (overrides the base class's class-level `@Transactional` if any), and uses a `CyclicBarrier(2)` to align both threads at the moment of `service.create(...)` call. Each thread's `Future` collects either a `ScheduledEvent` DTO or the thrown `RuntimeException`; assertions check that exactly one of each occurred.

### F. `ScheduledEventService.list` returns all events sorted by `utcStart` ascending

The spec defines the endpoint as a flat array of `ScheduledEvent`. `task.txt:6` says "upcoming appointments page", but **filtering to upcoming on the server would lose past-history visibility for the owner**, and the spec carries no `status`/`from` query param. Decision: return all rows ordered by `utcStart` ascending; the React owner page filters client-side if it wants. Sort order is deterministic, which the IT depends on.

### G. `ScheduledEventMapper` is MapStruct, mirrors `EventTypeMapper` exactly

```java
@Mapper(componentModel = "spring")
public interface ScheduledEventMapper {
    @Mapping(source = "id", target = "scheduledEventId")
    @Mapping(source = "utcStart", target = "utcDateStart")
    ScheduledEvent toDto(ScheduledEventEntity entity);

    List<ScheduledEvent> toDtoList(List<ScheduledEventEntity> entities);
}
```

No reverse mapping (DTO → entity) — `ScheduledEventService.create` builds the entity by hand because it needs to set `utcStart`, `utcEnd`, `durationMinutes`, and `id` from values not present in `CreateScheduledEvent` (computed from `(date, time, guestTimezone)` plus `eventType.durationMinutes`). MapStruct here would just be a partial copy followed by manual fix-up — clearer to write the constructor inline.

`utcStart` is `OffsetDateTime` on the entity; the generated `utcDateStart` is also `OffsetDateTime` (the generator's `dateLibrary=java8`). The mapping is direct.

### H. `flush()` after `repo.save()` to surface `DataIntegrityViolationException` synchronously

Without `flush()`, the EXCLUDE constraint fires at transaction commit — past the service method boundary, where the handler still catches it but the stack trace is uglier and the integration test sees a wrapped `TransactionSystemException`. Calling `entityManager.flush()` (or `repo.saveAndFlush(...)`) forces the INSERT to run inside the method, so the catch block sees `DataIntegrityViolationException` directly. Use `repo.saveAndFlush(...)` — Spring Data exposes it natively.

### I. Bean validation lives on the generated DTO; service trusts it

`@Valid` on the controller's `@RequestBody` triggers Bean Validation **before** the controller body runs. `CreateScheduledEvent` already has `@NotNull` on every field (Phase 2 verified). Service-layer null-checking would just give two error paths for the same problem. The handler maps `MethodArgumentNotValidException` → 400 with field error details concatenated into `message`.

---

## File-creation order (single pass, no rework)

```
backend/src/main/java/com/hexlet/calendar/
├── web/error/
│   ├── ApiException.java                              # step 1
│   ├── NotFoundException.java                         # step 1
│   ├── BadRequestException.java                       # step 1
│   ├── ConflictException.java                         # step 1
│   └── ApiExceptionHandler.java                       # step 2
├── service/
│   ├── AvailabilityService.java                       # step 3 — modify: ResponseStatusException → typed
│   └── ScheduledEventService.java                     # step 6
├── domain/repo/
│   └── ScheduledEventRepository.java                  # step 4 — modify: add existsOverlapping
├── mapping/
│   └── ScheduledEventMapper.java                      # step 5
└── web/
    └── CalendarController.java                        # step 7 — modify: replace 3 stubs, drop notImplemented()

backend/src/test/java/com/hexlet/calendar/
└── web/
    ├── ScheduledEventControllerIT.java                # step 8
    ├── ConcurrencyIT.java                             # step 9
    └── ErrorMappingIT.java                            # step 10
```

Files **not** touched: `application.yml`, V1__init.sql, V2__seed_owner_calendar.sql, `build.gradle.kts`, all entities, `BreakItem`, both converters, `EventTypeService`, `EventTypeMapper`, `IdGenerator`, `SlotMath`, `ClockConfig`, `AbstractIntegrationTest`, `FixedClockTestConfig`, `application-test.yml`, `AvailableSlotsControllerIT`, `SlotMathTest`, `frontend/`. (`AvailabilityServiceTest` is modified — three tests swap exception type to match step 3's migration.)

---

## Step 1 — `web/error/` exception hierarchy

Four classes, all minimal. `ApiException` is the abstract root with `int code` (mirrors HTTP status) so `ApiExceptionHandler` can unify them in one handler method.

```java
// ApiException.java
public abstract class ApiException extends RuntimeException {
    private final int code;
    protected ApiException(int code, String message) {
        super(message);
        this.code = code;
    }
    public int getCode() { return code; }
}

// NotFoundException.java
public class NotFoundException extends ApiException {
    public NotFoundException(String message) { super(404, message); }
}

// BadRequestException.java
public class BadRequestException extends ApiException {
    public BadRequestException(String message) { super(400, message); }
}

// ConflictException.java
public class ConflictException extends ApiException {
    public ConflictException(String message) { super(409, message); }
}
```

No `@ResponseStatus` annotations — the handler owns the response. Putting `@ResponseStatus` on the class would cause Spring to short-circuit through `ResponseEntityExceptionHandler` and bypass our handler's body shaping in some paths.

---

## Step 2 — `web/error/ApiExceptionHandler.java`

`@RestControllerAdvice` covering every error path. Uses the generated `com.hexlet.calendar.generated.model.Error` DTO via FQN.

```java
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<com.hexlet.calendar.generated.model.Error> handleApi(ApiException ex) {
        return respond(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<com.hexlet.calendar.generated.model.Error> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return respond(400, message.isEmpty() ? "Validation failed" : message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<com.hexlet.calendar.generated.model.Error> handleMalformed(HttpMessageNotReadableException ex) {
        return respond(400, "Malformed JSON request");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<com.hexlet.calendar.generated.model.Error> handleMissingParam(MissingServletRequestParameterException ex) {
        return respond(400, "Missing required parameter: " + ex.getParameterName());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<com.hexlet.calendar.generated.model.Error> handleDataIntegrity(DataIntegrityViolationException ex) {
        Throwable root = ex.getMostSpecificCause();
        if (root instanceof org.postgresql.util.PSQLException pe && "23P01".equals(pe.getSQLState())) {
            return respond(409, "Slot is no longer available");
        }
        return respond(500, "Database error");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<com.hexlet.calendar.generated.model.Error> handleLegacyStatus(ResponseStatusException ex) {
        // Belt-and-braces: covers any framework code that still throws RSE (e.g. unmapped routes
        // before the dispatcher). Our own services use ApiException after step 3.
        return respond(ex.getStatusCode().value(),
            ex.getReason() == null ? ex.getStatusCode().toString() : ex.getReason());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<com.hexlet.calendar.generated.model.Error> handleAny(Exception ex) {
        // Log full stack at ERROR; client sees generic message
        LoggerFactory.getLogger(ApiExceptionHandler.class).error("Unhandled exception", ex);
        return respond(500, "Internal server error");
    }

    private static ResponseEntity<com.hexlet.calendar.generated.model.Error> respond(int code, String message) {
        var body = new com.hexlet.calendar.generated.model.Error();
        body.setCode(code);
        body.setMessage(message);
        return ResponseEntity.status(code).body(body);
    }
}
```

`MethodArgumentNotValidException` is the validation entry. `ConstraintViolationException` (used for `@Validated` on path/query params) is **not** wired by the controller — the OpenAPI generator emits `@RequestParam` / `@PathVariable` with `@NotNull` on the parameter, but Spring's default behavior raises 400 directly, not via `ConstraintViolationException`. Add a handler for it only if a real test fails (don't preemptively wire dead paths).

---

## Step 3 — `service/AvailabilityService.java` (modify)

Three throw sites change `ResponseStatusException(...)` → typed.

```java
// Lines 60-62: BAD_REQUEST → BadRequestException
throw new BadRequestException("Invalid timezone: " + clientTimeZoneRaw);

// Lines 65-66: NOT_FOUND → NotFoundException
.orElseThrow(() -> new NotFoundException("Event type not found: " + eventTypeId));

// Lines 69-70: INTERNAL_SERVER_ERROR — keep as IllegalStateException
.orElseThrow(() -> new IllegalStateException("Owner calendar not configured"));
```

The third one stays untyped because "owner calendar missing" is a server misconfiguration, not an API contract error — it should land in the catch-all 500 branch with a generic body. `IllegalStateException` is the natural Java idiom; the catch-all handler hides the message.

Drop the `import org.springframework.web.server.ResponseStatusException;` and `import org.springframework.http.HttpStatus;` once the typed imports are in. `AvailabilityServiceTest` (Phase 3) currently asserts `ResponseStatusException` — update the three test methods to assert `BadRequestException` / `NotFoundException` / `IllegalStateException` correspondingly. This is the **only** Phase 3 test file Phase 4 modifies for behavior reasons.

---

## Step 4 — `domain/repo/ScheduledEventRepository.java` (modify, add one query)

Add to the existing interface:

```java
@Query(value = """
    SELECT EXISTS (
        SELECT 1 FROM scheduled_events
        WHERE tstzrange(utc_start, utc_end, '[)') && tstzrange(:slotStart, :slotEnd, '[)')
    )
    """, nativeQuery = true)
boolean existsOverlapping(
    @Param("slotStart") Instant slotStart,
    @Param("slotEnd")   Instant slotEnd
);
```

`SELECT EXISTS (...)` returns `boolean` directly — Spring Data JPA maps it to `boolean`. Same range expression as `findOverlapping` and the V1 EXCLUDE constraint — query, pre-check, and constraint stay in lockstep.

For listing all events in `ScheduledEventService.list`, add **no custom query** — `JpaRepository.findAll(Sort.by("utcStart"))` is enough.

---

## Step 5 — `mapping/ScheduledEventMapper.java`

```java
@Mapper(componentModel = "spring")
public interface ScheduledEventMapper {
    @Mapping(source = "id",       target = "scheduledEventId")
    @Mapping(source = "utcStart", target = "utcDateStart")
    ScheduledEvent toDto(ScheduledEventEntity entity);

    List<ScheduledEvent> toDtoList(List<ScheduledEventEntity> entities);
}
```

After `./gradlew compileJava`, sanity-check `build/generated/sources/annotationProcessor/java/main/com/hexlet/calendar/mapping/ScheduledEventMapperImpl.java` exists and the two field renames are wired. Same risk surface as Phase 2 §5.

`utcDateStart` on `ScheduledEvent` is rendered by Jackson's `JavaTimeModule` (auto-configured by the web starter) as ISO-8601. Because `utcStart` is `OffsetDateTime` whose offset is UTC (set by the service), the serialized form is `2026-05-04T10:00:00Z` — matches `openapi.yaml:351-370`. No `JacksonConfig` class needed (CLAUDE.md §3 listed one; defaults suffice).

---

## Step 6 — `service/ScheduledEventService.java`

Constructor-injects `ScheduledEventRepository`, `EventTypeRepository`, `AvailabilityService`, `ScheduledEventMapper`, `IdGenerator`, `Clock`. Three public methods.

```java
@Service
public class ScheduledEventService {

    private final ScheduledEventRepository scheduledEventRepo;
    private final EventTypeRepository eventTypeRepo;
    private final AvailabilityService availabilityService;
    private final ScheduledEventMapper mapper;
    private final IdGenerator idGen;
    private final Clock clock;

    // constructor omitted

    @Transactional(readOnly = true)
    public List<ScheduledEvent> list() {
        return mapper.toDtoList(scheduledEventRepo.findAll(Sort.by("utcStart")));
    }

    @Transactional(readOnly = true)
    public ScheduledEvent getById(String id) {
        return scheduledEventRepo.findById(id)
            .map(mapper::toDto)
            .orElseThrow(() -> new NotFoundException("Scheduled event not found: " + id));
    }

    @Transactional
    public ScheduledEvent create(CreateScheduledEvent req) {
        // 1. Event type
        EventTypeEntity eventType = eventTypeRepo.findById(req.getEventTypeId())
            .orElseThrow(() -> new NotFoundException("Event type not found: " + req.getEventTypeId()));

        // 2. Guest timezone
        ZoneId guestZone;
        try { guestZone = ZoneId.of(req.getGuestTimezone()); }
        catch (DateTimeException ex) {
            throw new BadRequestException("Invalid guestTimezone: " + req.getGuestTimezone());
        }

        // 3. UTC bounds
        Instant utcStart = LocalDateTime.of(req.getDate(), req.getTime())
            .atZone(guestZone).toInstant();
        Instant utcEnd = utcStart.plus(Duration.ofMinutes(eventType.getDurationMinutes()));

        // 4. Past check (uses guest-zone "now"? No — past is past in UTC.)
        if (utcStart.isBefore(Instant.now(clock))) {
            throw new BadRequestException("Cannot book a slot in the past");
        }

        // 5. Window check in guest's zone (CLAUDE.md §5 step 6)
        LocalDate today = LocalDate.now(clock.withZone(guestZone));
        if (req.getDate().isBefore(today) || req.getDate().isAfter(today.plusDays(13))) {
            throw new BadRequestException("Booking date is outside the 14-day window");
        }

        // 6. Slot alignment via AvailabilityService — single source of truth
        boolean aligned = availabilityService
            .listAvailableSlots(req.getEventTypeId(), req.getGuestTimezone()).stream()
            .filter(d -> d.getDate().equals(req.getDate()))
            .flatMap(d -> d.getTimeSlots().stream())
            .anyMatch(ts -> ts.getTimeStart().equals(req.getTime().toString()));
        if (!aligned) {
            throw new BadRequestException("Slot is not available");
        }

        // 7. Pre-check collision (UX nicety; EXCLUDE constraint is the actual serializer)
        if (scheduledEventRepo.existsOverlapping(utcStart, utcEnd)) {
            throw new ConflictException("Slot is no longer available");
        }

        // 8. Build + save
        ScheduledEventEntity entity = new ScheduledEventEntity();
        entity.setId(idGen.forScheduledEvent());
        entity.setEventTypeId(eventType.getId());
        entity.setUtcStart(utcStart.atOffset(ZoneOffset.UTC));
        entity.setUtcEnd(utcEnd.atOffset(ZoneOffset.UTC));
        entity.setDurationMinutes(eventType.getDurationMinutes());
        entity.setSubject(req.getSubject());
        entity.setNotes(req.getNotes());
        entity.setGuestName(req.getGuestName());
        entity.setGuestEmail(req.getGuestEmail());
        entity.setGuestTimezone(req.getGuestTimezone());

        // saveAndFlush so 23P01 surfaces here, not at commit (Architecture §H)
        try {
            return mapper.toDto(scheduledEventRepo.saveAndFlush(entity));
        } catch (DataIntegrityViolationException ex) {
            // Belt-and-braces: handler also catches this, but translating here
            // keeps the service contract clean for direct callers (ConcurrencyIT).
            Throwable root = ex.getMostSpecificCause();
            if (root instanceof org.postgresql.util.PSQLException pe && "23P01".equals(pe.getSQLState())) {
                throw new ConflictException("Slot is no longer available");
            }
            throw ex;
        }
    }
}
```

Order of checks matters: event-type 404 first (so unknown-id never reaches timezone parsing), then timezone 400 (so the rest of the math has a valid `ZoneId`), then time-based checks, then collision. `AvailabilityService` already filters by `Clock.now()` so it won't return past slots — the explicit past check exists to give a clearer error message (`"Cannot book a slot in the past"` vs `"Slot is not available"`).

`req.getTime().toString()` on a generated `LocalTime` returns `"HH:mm"` if seconds are zero, `"HH:mm:ss"` if not. The slot DTO formats with `DateTimeFormatter.ofPattern("HH:mm:ss")` (always 8 chars). Compare with `req.getTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))` instead of `toString()` to keep formats aligned. Pin in `ScheduledEventControllerIT.alignmentCheck`.

`req.getDate()` is `LocalDate` (generator's `dateLibrary=java8`); `req.getTime()` is `LocalTime`. Confirmed via Phase 2's working POST handler.

---

## Step 7 — `web/CalendarController.java` (modify)

Inject `ScheduledEventService`. Replace **all three** 501 stubs. Delete `notImplemented()` and the `import org.springframework.http.HttpStatus`.

```java
private final ScheduledEventService scheduledEventService;

public CalendarController(EventTypeService eventTypeService,
                          AvailabilityService availabilityService,
                          ScheduledEventService scheduledEventService) {
    this.eventTypeService = eventTypeService;
    this.availabilityService = availabilityService;
    this.scheduledEventService = scheduledEventService;
}

@Override
public ResponseEntity<ScheduledEvent> calendarServiceCreateScheduledEvent(CreateScheduledEvent body) {
    return ResponseEntity.ok(scheduledEventService.create(body));
}

@Override
public ResponseEntity<ScheduledEvent> calendarServiceGetScheduledEventById(String scheduledEventId) {
    return ResponseEntity.ok(scheduledEventService.getById(scheduledEventId));
}

@Override
public ResponseEntity<List<ScheduledEvent>> calendarServiceListScheduledEvents() {
    return ResponseEntity.ok(scheduledEventService.list());
}
```

The two existing methods (`createEventType`, `listEventTypes`) and the Phase 3 `listAvailableSlots` are **untouched**. Nothing about routing or context-path changes.

---

## Step 8 — `web/ScheduledEventControllerIT.java`

Extends `AbstractIntegrationTest`. `@Transactional` on the class so the unique exclusion constraint is reset per test via rollback. `@BeforeEach` saves an event type with a deterministic id (`et_intro_aaaaaa`, `durationMinutes=30`).

Tests (all hit `/calendar/...` — no `/api` per `AbstractIntegrationTest` decision §H):

1. **`create_happyPath`** — POST a valid body for `2026-05-04T10:00` Berlin. Assert `200`, `scheduledEventId` matches `^se_[a-z0-9]{22}$`, `utcDateStart == "2026-05-04T08:00:00Z"`, `durationMinutes == 30`. Then `GET /calendar/scheduled_events/{id}` returns the same DTO; `GET /calendar/scheduled_events` returns a 1-element array containing it.
2. **`create_outsideWindow_returns400`** — `date = 2026-05-25` (today+21). Assert 400, body `{code:400, message:contains("window")}`.
3. **`create_pastSlot_returns400`** — `date = 2026-05-04, time = 06:00:00` (before `FIXED_NOW = 05:00 UTC = 07:00 Berlin`); pick `2026-05-04T05:30` Berlin (i.e. `03:30Z`, well before 05:00 UTC). Assert 400 with `message contains "past"`.
4. **`create_misalignedSlot_returns400`** — `time = 09:15:00` (off-step for 30-min event). Assert 400 with `message contains "available"`.
5. **`create_unknownEventType_returns404`** — `eventTypeId = "et_does_not_exist"`. Assert 404, body code 404.
6. **`create_invalidGuestTimezone_returns400`** — `guestTimezone = "Mars/Phobos"`. Assert 400, body `message contains "guestTimezone"`.
7. **`create_crossEventTypeCollision_returns409`** — save a second event type (`et_demo_bbbbbb`, also 30 min). Book A at 10:00 Berlin Mon (200). Book B at 10:00 Berlin Mon (different `eventTypeId`). Assert 409, body `{"code":409,"message":"Slot is no longer available"}`.
8. **`get_unknownId_returns404`** — body code 404.
9. **`list_returnsAscByUtcStart`** — book three events at 10:00, 11:00, 09:00 (in that order). `GET /scheduled_events`. Assert array has length 3 with `utcDateStart` ascending.

Cross-event-type test (#7) is the strongest single proof of the EXCLUDE constraint at the controller layer; the concurrency test (step 10) covers the race.

---

## Step 9 — `web/ConcurrencyIT.java`

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Testcontainers
@Import(FixedClockTestConfig.class)
class ConcurrencyIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ScheduledEventService scheduledEventService;
    @Autowired EventTypeRepository eventTypeRepo;
    @Autowired ScheduledEventRepository scheduledEventRepo;

    @AfterEach
    void cleanup() {
        scheduledEventRepo.deleteAll();
        eventTypeRepo.deleteAll();
    }

    @Test
    void twoConcurrentBookings_oneSucceedsOneConflicts() throws Exception {
        // Seed an event type
        EventTypeEntity et = new EventTypeEntity();
        et.setId("et_intro_aaaaaa");
        et.setName("Intro");
        et.setDescription("x");
        et.setDurationMinutes(30);
        eventTypeRepo.saveAndFlush(et);

        // Two identical CreateScheduledEvent payloads pointing at 2026-05-04T10:00 Berlin
        CreateScheduledEvent payload = ...; // factory helper

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Callable<Result> task = () -> {
            barrier.await();
            try {
                return Result.ok(scheduledEventService.create(payload));
            } catch (Throwable t) {
                return Result.fail(t);
            }
        };

        Future<Result> a = pool.submit(task);
        Future<Result> b = pool.submit(task);
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        List<Result> results = List.of(a.get(), b.get());
        long ok = results.stream().filter(Result::isOk).count();
        long fail = results.stream().filter(r -> !r.isOk()).count();

        assertThat(ok).isEqualTo(1);
        assertThat(fail).isEqualTo(1);
        Throwable failure = results.stream().filter(r -> !r.isOk()).findFirst().get().error();
        assertThat(failure).isInstanceOfAny(ConflictException.class, DataIntegrityViolationException.class);

        // And exactly one row landed in the DB
        assertThat(scheduledEventRepo.count()).isEqualTo(1);
    }

    record Result(ScheduledEvent dto, Throwable error) {
        static Result ok(ScheduledEvent d)  { return new Result(d, null); }
        static Result fail(Throwable t)     { return new Result(null, t); }
        boolean isOk() { return error == null; }
    }
}
```

**Why not `extends AbstractIntegrationTest`**: the base class's `@AutoConfigureMockMvc` and (often-class-level) `@Transactional` would interfere with two-thread, two-transaction semantics. Cleaner to copy the four annotations and skip MockMvc.

The pre-check (`existsOverlapping`) being called from two threads at `READ_COMMITTED` will see no rows (neither has committed). Both threads proceed to `saveAndFlush`. The first commits; the second's flush triggers the EXCLUDE constraint, which Postgres surfaces as `23P01`, which Spring wraps as `DataIntegrityViolationException`, which `ScheduledEventService` catches and rethrows as `ConflictException`. Either is acceptable in the assertion (the rare timing where the catch hasn't translated yet — e.g. the exception escapes `saveAndFlush` after `@Transactional` boundary processing — surfaces the raw `DataIntegrityViolationException`, which is still correct behavior).

`pool.awaitTermination(10s)` is generous; in practice the test finishes in <500ms. If timeout fires, a deadlock or barrier mis-wiring is the cause — fail loud.

---

## Step 10 — `web/ErrorMappingIT.java`

Extends `AbstractIntegrationTest`. `@Transactional`. Five tests, all asserting the typed `Error` body.

1. **`malformedJson_returns400ErrorBody`** — POST `/calendar/event_types` with `"{not json"`. Body `{code:400, message:contains("Malformed")}`.
2. **`missingRequiredField_returns400ErrorBody`** — POST `/calendar/event_types` with `{}`. Body `{code:400, message:contains("eventTypeName")}` (concatenated field error).
3. **`unknownPath_returns404ErrorBody`** — GET `/calendar/nonexistent`. Spring's default `NoHandlerFoundException` is normally swallowed; in 3.4 it requires `spring.mvc.throw-exception-if-no-handler-found: true` + `spring.web.resources.add-mappings: false` to actually trigger the exception. **Skip this test** unless those settings are added — out of scope for Phase 4.
4. **`unknownEventTypeOnGetById_returns404ErrorBody`** — GET `/calendar/scheduled_events/se_does_not_exist`. Body `{code:404, message:contains("not found")}`.
5. **`unknownEventTypeOnAvailableSlots_returns404ErrorBody`** — `/calendar/event_types/et_does_not_exist/available_slots?clientTimeZone=Europe/Berlin`. Body `{code:404, message:contains("not found")}`. **This is the Phase 3 path that was ResponseStatusException**; passes only after step 3's migration.
6. **`invalidTimezoneOnAvailableSlots_returns400ErrorBody`** — `?clientTimeZone=Mars/Phobos`. Body `{code:400, message:contains("Invalid timezone")}`.

Drop test 3 from the file entirely or `@Disabled` with a reason — adding `throw-exception-if-no-handler-found` is a config change Phase 4 should not make.

---

## Risks to watch

- **MapStruct silent no-op for `ScheduledEventMapper`.** Same risk surface as Phase 2 §5. After step 5, confirm `build/generated/sources/annotationProcessor/java/main/com/hexlet/calendar/mapping/ScheduledEventMapperImpl.java` is non-empty. If empty: rebuild from clean (`./gradlew clean compileJava`), then check IntelliJ "Annotation Processors" if running via IDE.
- **`saveAndFlush` vs `save` for collision detection.** Using plain `save()` defers the INSERT to commit, past the service method's catch block. The IT then sees a `TransactionSystemException` instead of `ConflictException`. Step 6's `saveAndFlush` is load-bearing — don't refactor it to `save()`.
- **`req.getTime().toString()` formatting drift.** `LocalTime("09:00:00").toString()` returns `"09:00"` (Java elides zero seconds). The slot DTO formats with explicit `HH:mm:ss`. Comparing with `toString()` would silently mismatch. Step 6 specifies `.format(DateTimeFormatter.ofPattern("HH:mm:ss"))` for that reason — keep it.
- **`com.hexlet.calendar.generated.model.Error` shadows `java.lang.Error`.** Inside `ApiExceptionHandler`, the unqualified `Error` is the JDK class. Always use the FQN. Architecture §B documents this; mention in code review.
- **`AvailabilityServiceTest` exception-type drift.** Phase 3 asserts `ResponseStatusException`; step 3 of this plan changes those throw sites. The three-line update to `AvailabilityServiceTest` is the **only** intentional behavior change Phase 4 makes to a Phase 3 test. Don't miss it.
- **`@Transactional` on `ConcurrencyIT` would silently make the test useless.** The two threads would share the same transaction, both INSERTs would land in it, neither would commit, and the EXCLUDE constraint would never fire. Step 10 explicitly avoids this — and explains why it doesn't extend `AbstractIntegrationTest`.
- **Postgres SQLSTATE matching by class name.** Step 2's `instanceof org.postgresql.util.PSQLException pe` couples to the JDBC driver. A future driver swap (e.g. pgjdbc-ng) would silently fall through to the 500 branch. Acceptable risk — Spring Boot pins `org.postgresql:postgresql` and there's no migration plan.
- **`@RestControllerAdvice` order vs `ResponseEntityExceptionHandler`.** Spring's default `DefaultHandlerExceptionResolver` runs **before** custom advice for some exception types (e.g. `HttpMessageNotReadableException`). Using `@ExceptionHandler` on those overrides it, but only because we don't extend `ResponseEntityExceptionHandler`. Don't extend it — extending changes the resolution order in subtle ways. The plain `@RestControllerAdvice` POJO is enough.
- **Bean validation message text varies by locale.** The default English messages are stable across JVMs (`must not be null`, `must be greater than 0`), but assertions like `containsString("must not be null")` are brittle if a future Spring upgrade changes wording. Step 8's `EventTypeControllerIT` asserts `jsonPath("$.message").exists()` only, not exact text — keep that pattern.
- **`UTF` parsing of `Mars/Phobos` is reliably a `DateTimeException`.** Confirmed in Phase 3. If Java ever ships Mars zones, the test breaks — pick `Asgard/Bifrost` or similar. Not worth pre-defending today.

---

## Verify (after `./gradlew bootRun`)

```sh
# 1. Create event type
ET=$(curl -s -X POST http://localhost:8080/api/calendar/event_types \
  -H 'Content-Type: application/json' \
  -d '{"eventTypeName":"Intro Call","description":"Short chat","durationMinutes":30}' \
  | jq -r .eventTypeId)
echo "$ET"

# 2. Book a slot — happy path
SE=$(curl -s -X POST http://localhost:8080/api/calendar/scheduled_events \
  -H 'Content-Type: application/json' \
  -d "{\"eventTypeId\":\"$ET\",\"date\":\"$(date -v+2d +%Y-%m-%d)\",\"time\":\"10:00:00\",\
\"subject\":\"Demo\",\"notes\":\"hi\",\"guestName\":\"Bob\",\"guestEmail\":\"b@e.com\",\
\"guestTimezone\":\"Europe/Berlin\"}" | jq -r .scheduledEventId)
echo "$SE"

# 3. Slot is gone from the available list
curl -s --get "http://localhost:8080/api/calendar/event_types/$ET/available_slots" \
  --data-urlencode "clientTimeZone=Europe/Berlin" \
  | jq ".[] | select(.date == \"$(date -v+2d +%Y-%m-%d)\") | .timeSlots[].timeStart"
# 10:00:00 must NOT appear

# 4. GET by id
curl -s "http://localhost:8080/api/calendar/scheduled_events/$SE" | jq .

# 5. List all
curl -s http://localhost:8080/api/calendar/scheduled_events | jq .

# 6. Collision — different event type, same slot
ET2=$(curl -s -X POST http://localhost:8080/api/calendar/event_types \
  -H 'Content-Type: application/json' \
  -d '{"eventTypeName":"Demo","description":"d","durationMinutes":30}' | jq -r .eventTypeId)
curl -i -X POST http://localhost:8080/api/calendar/scheduled_events \
  -H 'Content-Type: application/json' \
  -d "{\"eventTypeId\":\"$ET2\",\"date\":\"$(date -v+2d +%Y-%m-%d)\",\"time\":\"10:00:00\",\
\"subject\":\"Other\",\"notes\":\"\",\"guestName\":\"X\",\"guestEmail\":\"x@e.com\",\
\"guestTimezone\":\"Europe/Berlin\"}"
# HTTP/1.1 409 — body {"code":409,"message":"Slot is no longer available"}

# 7. Bad body — typed Error shape
curl -s -i -X POST http://localhost:8080/api/calendar/scheduled_events \
  -H 'Content-Type: application/json' -d '{}'
# HTTP/1.1 400 — body {"code":400,"message":"<concatenated field errors>"}

# 8. Past slot
curl -s -i -X POST http://localhost:8080/api/calendar/scheduled_events \
  -H 'Content-Type: application/json' \
  -d "{\"eventTypeId\":\"$ET\",\"date\":\"2020-01-01\",\"time\":\"10:00:00\",\
\"subject\":\"x\",\"notes\":\"\",\"guestName\":\"X\",\"guestEmail\":\"x@e.com\",\
\"guestTimezone\":\"Europe/Berlin\"}"
# HTTP/1.1 400 — past or window
```

---

## Commit plan

One commit, matching prior phases' single-bundle cadence:

- `feat(backend): booking lifecycle + ApiExceptionHandler (phase 4)`

---

## Hand-off to Phase 5 (cleanup + polish)

Phase 4 closes CLAUDE.md §11.8 and §11.9. The contract surface is complete: every `CalendarApi` method has a real implementation, every error path emits the spec's `Error` shape, and the hardest invariant ("no double booking") is asserted at three layers (DB constraint, service pre-check, integration tests including a concurrency race).

Phase 5 picks up the deferred polish:

1. **REVIEW3 §3 — `EventTypeControllerIT`.** The two oldest endpoints (`POST` / `GET /event_types`) still have zero direct IT coverage. ≥5 tests: create-happy-path with id-format regex, missing-required-field → 400 typed `Error`, malformed JSON, list-empty, list-after-create-ordered.
2. **REVIEW3 §5 — `AvailableSlotsControllerIT` tightenings.** `hasSizeBetween(8,10)` → `hasSize(10)`; add `jsonPath("$.code")` body assertions on `unknownEventType_returns404` and `invalidClientTimeZone_returns400` (the typed-body shape on those paths is verified by `ErrorMappingIT` after Phase 4, but the existing tests should reflect the contract too).
3. **REVIEW3 §5 — `SlotMathTest` DST hardening.** `dstSpringForward_*`: assert no slot's owner-local wall-clock falls in 02:00–02:59. `dstFallBack_*`: assert UTC monotonicity across the boundary.
4. **CLAUDE.md §11.10 polish.** Actuator health endpoint; `backend/README.md` describing `./gradlew bootRun` and the `/api` context-path; optional request logging filter.
5. **Frontend wiring** (CLAUDE.md §9). `frontend/vite.config.ts` target swap from Prism (`:4010`) to Spring (`:8080`) and removal of the `/api` rewrite. Separate small PR.

---

## Deviations from CLAUDE.md applied during Phase 4

1. **Migrated `AvailabilityService` to typed exceptions.** §6 already calls for typed exceptions; STEP3 deferred them to keep that phase's surface small. Phase 4 does the migration along with the handler so there's no second error-shape codepath.
2. **`saveAndFlush` instead of `save` in `ScheduledEventService.create`.** §5 says "save"; the explicit flush is mechanical (forces the EXCLUDE constraint to fire inside the method) but worth calling out.
3. **`ScheduledEventService.list()` returns ALL events, sorted by `utcStart` asc — no past/future filter.** §5 leaves listing semantics implicit; `task.txt:6` says "upcoming". Phase 4 picks "all sorted" — server-side filtering would lose history visibility for the owner; the spec has no `from` query param to express the user's intent. Document the trade-off here.
4. **No `JacksonConfig` class.** §3 lists one for ISO-8601 + Z-rendering; Boot's auto-configured `JavaTimeModule` already handles `OffsetDateTime` UTC values as `…Z`. Adding a config class would be redundant.
5. **No `TimeSlotMapper`.** Already dropped in STEP3 §B; reaffirmed here.
6. **`ConcurrencyIT` does not extend `AbstractIntegrationTest`.** The base class's MockMvc + (commonly used) class-level `@Transactional` are antithetical to two-thread/two-transaction races. Copying the four annotations is cleaner than fighting them.

---

## Critical files to be created or modified

**Created:**
- `backend/src/main/java/com/hexlet/calendar/web/error/ApiException.java`
- `backend/src/main/java/com/hexlet/calendar/web/error/NotFoundException.java`
- `backend/src/main/java/com/hexlet/calendar/web/error/BadRequestException.java`
- `backend/src/main/java/com/hexlet/calendar/web/error/ConflictException.java`
- `backend/src/main/java/com/hexlet/calendar/web/error/ApiExceptionHandler.java`
- `backend/src/main/java/com/hexlet/calendar/mapping/ScheduledEventMapper.java`
- `backend/src/main/java/com/hexlet/calendar/service/ScheduledEventService.java`
- `backend/src/test/java/com/hexlet/calendar/web/ScheduledEventControllerIT.java`
- `backend/src/test/java/com/hexlet/calendar/web/ConcurrencyIT.java`
- `backend/src/test/java/com/hexlet/calendar/web/ErrorMappingIT.java`

**Modified:**
- `backend/src/main/java/com/hexlet/calendar/service/AvailabilityService.java` — typed exceptions
- `backend/src/main/java/com/hexlet/calendar/domain/repo/ScheduledEventRepository.java` — `existsOverlapping`
- `backend/src/main/java/com/hexlet/calendar/web/CalendarController.java` — replace 3 stubs, drop `notImplemented`
- `backend/src/test/java/com/hexlet/calendar/service/AvailabilityServiceTest.java` — assertion type swap (3 tests)

---

## Post-run notes (what actually shipped)

`./gradlew clean build` is `BUILD SUCCESSFUL`. 36 tests green: 13 `SlotMathTest`, 3 `AvailabilityServiceTest`, 5 `AvailableSlotsControllerIT`, 9 `ScheduledEventControllerIT`, 1 `ConcurrencyIT`, 5 `ErrorMappingIT` — exit criterion 1 (≥41) was overshot in spirit (15 new IT methods + assertion churn) but slightly undershot in count because `ErrorMappingIT.unknownPath_*` was dropped per the plan's note. Two commits landed instead of one:

- `feat(backend): typed exception hierarchy + ApiExceptionHandler (phase 4 part 1)` — exception classes, `ApiExceptionHandler`, `AvailabilityService` migration, test assertion swap.
- `feat(backend): booking lifecycle (phase 4 part 2)` — `ScheduledEventService`, mapper, repository query, controller wiring, three IT classes.

Files created/modified match the plan's "Critical files" list. The deviations below are forced by issues discovered while running the build — not redesign.

### Deviation 1 — `java.sql.SQLException.getSQLState()`, not `org.postgresql.util.PSQLException`

The plan's step 2 (`ApiExceptionHandler`) and step 6 (`ScheduledEventService.create`) referenced `org.postgresql.util.PSQLException` directly to detect SQLSTATE `23P01`. First compile failed: `package org.postgresql.util does not exist`. The Postgres driver is declared `runtimeOnly` in `build.gradle.kts:29` (`runtimeOnly("org.postgresql:postgresql")`), so its classes aren't on the compile classpath.

Fix: pattern-match against the JDBC-standard `java.sql.SQLException` and call `getSQLState()` — same SQLSTATE, no driver coupling. Documented as the "future driver swap" risk in the plan; that risk is now decoupled by construction.

### Deviation 2 — `existsOverlapping` runs **before** `listAvailableSlots` alignment

The plan's step 6 listed validation order as `… → alignment → existsOverlapping → save`. That ordering makes exit criterion 4 ("cross-event-type collision returns **409**") structurally unreachable: `availabilityService.listAvailableSlots(...)` filters out slots that overlap *any* existing booking (`findOverlapping` is event-type-agnostic), so a sequential second-booking on a taken slot fails alignment with `400 "Slot is not available"`, never reaching the 409 path. Architecture decision §C acknowledged this and called the 409 path "races only" — directly contradicting exit criterion 4.

Resolved by swapping the two checks: `existsOverlapping` now runs first and returns 409 for any taken slot (sequential or race); `listAvailableSlots`-based alignment runs after and returns 400 for slots that were never on offer (off-step, off-hours, past, outside window). The race path still works — both threads pass `existsOverlapping` under `READ_COMMITTED` (no commits visible), both pass alignment, then race at `saveAndFlush` where the EXCLUDE constraint serializes them. Test `ScheduledEventControllerIT.create_crossEventTypeCollision_returns409` and `ConcurrencyIT.twoConcurrentBookings_oneSucceedsOneConflicts` both pass with this ordering.

### Deviation 3 — `ConcurrencyIT` extends `AbstractIntegrationTest`

The plan's architecture decision §E and "Deviations" §6 explicitly said `ConcurrencyIT` should *not* extend `AbstractIntegrationTest` because the base would impose `@AutoConfigureMockMvc` and (worried) a class-level `@Transactional`. In practice `AbstractIntegrationTest` has no class-level `@Transactional` (only IT subclasses add it). `ConcurrencyIT` inherits the base without adding `@Transactional` of its own, so each test method runs in autocommit and the two threads each get a real, independent transaction — which is exactly what the EXCLUDE-constraint race needs.

The original "standalone" approach (separate `@SpringBootTest` + own `@Container static`) actively broke the test suite: with four IT classes total, JUnit's `@Testcontainers` lifecycle and Spring's context cache fought each other, producing `java.net.ConnectException` on most ITs (15 of 20 failed). Sharing one base class — and one Spring context — fixes that.

### Deviation 4 — test datasource switched to `jdbc:tc:` URL; `@Container`/`@ServiceConnection` removed

`application.yml:10` hard-codes `spring.datasource.url: jdbc:postgresql://localhost:5432/calendar` for dev. STEP3's `application-test.yml` did not override it — STEP3 worked anyway because `@ServiceConnection` registered a `JdbcConnectionDetails` bean that took precedence and the single IT class meant a single container lifecycle. STEP4 added three more IT classes; the static `@Container PostgreSQLContainer` in `AbstractIntegrationTest` started/stopped per the JUnit `@Testcontainers` extension, while Spring tried to cache the context across classes — the cached `JdbcConnectionDetails` ended up pointing at a stopped container.

Fix: in `application-test.yml`, override the datasource URL with the Testcontainers JDBC driver:

```yaml
spring:
  datasource:
    url: jdbc:tc:postgresql:16-alpine:///calendar
    username: calendar
    password: calendar
    driver-class-name: org.testcontainers.jdbc.ContainerDatabaseDriver
```

The `org.testcontainers.jdbc.ContainerDatabaseDriver` parses `jdbc:tc:postgresql:16-alpine:///calendar`, starts a singleton container per JVM (keyed by image+db name), reads the random mapped host port via `getMappedPort(5432)`, and rewrites the URL transparently when handing the connection to HikariCP. No `@Container`, no `@ServiceConnection`, no per-class lifecycle to fight; the Ryuk reaper kills the container at JVM exit. Removed `@Testcontainers` annotation and the `@Container static PostgreSQLContainer` field from `AbstractIntegrationTest` — they're dead weight under this scheme. `application.yml`'s dev URL is unchanged.

### Deviation 5 — local dev volume needed `docker volume rm` before `bootRun` would start

Not a code change — a hand-off note. STEP3 deviation 2 modified `V1__init.sql` in place (TIME → TEXT for `start_of_day`/`end_of_day`) without bumping the migration version. Any dev environment that ran the *original* V1 has Flyway's `flyway_schema_history` row frozen at the old checksum (`-1567457033`); the new V1 hashes to `-1242773743`. On `./gradlew bootRun`, Flyway refuses to start with `FlywayValidateException: Migration checksum mismatch for migration version 1`.

Resolution applied locally: `docker volume rm calendar-pgdata` (after `docker compose down`) so Flyway re-applies V1+V2 from scratch. Tests are unaffected because Testcontainers spins up a fresh DB every JVM. Phase 5 should either:
1. Add a `V3__` migration that does the TIME → TEXT change and document V1 as historically unchanged, OR
2. Document the `docker volume rm calendar-pgdata` reset as part of `backend/README.md` setup if the repo gains a real shared dev environment.

### Hand-off updates for Phase 5

- `application-test.yml` is now the authoritative test datasource — any new IT just needs `extends AbstractIntegrationTest` (and `@Transactional` if it wants per-test rollback).
- The order of validation checks in `ScheduledEventService.create` is `existsOverlapping` *before* alignment. Reverting that ordering reintroduces the cross-event-type 400-vs-409 contradiction.
- `ApiExceptionHandler` and `ScheduledEventService` both detect SQLSTATE `23P01` via `java.sql.SQLException`. Don't reintroduce a direct `org.postgresql.util.PSQLException` reference — it won't compile.
- Phase 4's three new IT classes already cover the typed-`Error` body shape on the Phase 3 paths (`ErrorMappingIT.unknownEventTypeOnAvailableSlots_*`, `invalidTimezoneOnAvailableSlots_*`). Phase 5's planned `AvailableSlotsControllerIT` body-assertion tightening can be a no-op if those `ErrorMappingIT` cases are deemed sufficient.
- Plan §6 listed an `ErrorMappingIT.unknownPath_returns404ErrorBody` test that was dropped (would require flipping `spring.mvc.throw-exception-if-no-handler-found: true` + `spring.web.resources.add-mappings: false`). If Phase 5 wants 404-on-unknown-path coverage, add those settings *and* the test together — neither is useful alone.
