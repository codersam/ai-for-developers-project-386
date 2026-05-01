# Backend Test Coverage Review

## Verdict: tests cover the slot generator well, but most of the API is unimplemented and untested

The tests look respectable in isolation, but they hide a much bigger problem: **the test suite covers only the parts that were actually built**, and what was built is barely half the spec.

## 1. Critical: most endpoints are `notImplemented()`, with no tests

`backend/src/main/java/com/hexlet/calendar/web/CalendarController.java:39-51` returns HTTP 501 for **3 of 6 endpoints**:

| Endpoint | Spec source | Status | Tests |
|---|---|---|---|
| `POST /calendar/event_types` | openapi.yaml:11 | implemented | **0** |
| `GET /calendar/event_types` | openapi.yaml:36 | implemented | **0** |
| `GET …/available_slots` | openapi.yaml:69 | implemented | 5 IT + unit |
| `POST /calendar/scheduled_events` | openapi.yaml:190 | **501** | **0** |
| `GET /calendar/scheduled_events` | openapi.yaml:170 | **501** | **0** |
| `GET …/scheduled_events/{id}` | openapi.yaml:215 | **501** | **0** |

`task.txt:6` ("View the upcoming appointments page") and `task.txt:11` ("Creates a booking for the selected slot") — the **two core user flows** — have no implementation and no tests. Codex shipped slot-listing only.

## 2. The "no double booking, even across event types" rule (task.txt:14) is untested

This is *the* hardest invariant in the spec. The plan called for:
- `ScheduledEventService.create` with pre-check + DB exclusion constraint
- `ConcurrencyIT` racing two POSTs at the same slot
- A cross-event-type collision IT (book A at 10:00, then book B at 10:00 → 409)

None exist. The DB-level guard (`EXCLUDE USING GIST` in `V1__init.sql`) is never exercised. The only thing approximating it is `AvailableSlotsControllerIT.existingBookingExcludesItsSlot`, which **inserts a row through the JPA repo and reads it back via the slots endpoint** — that proves nothing about the booking write path or about race conditions.

## 3. Even the implemented endpoints are under-tested

**EventType endpoints — zero coverage:**
- No test for `POST /calendar/event_types` (creation, ID-prefix format `et_…`, validation of `durationMinutes > 0`, blank name/description rejection, JSON parse errors).
- No test for `GET /calendar/event_types` (sort by `createdAt`, empty-list shape).
- `EventTypeService.create` (`service/EventTypeService.java:33`) goes through `IdGenerator.forEventType(name)` — the slug logic is completely untested.

**Available-slots endpoint — gaps:**
- `clientTimeZone` is a *required* query param (openapi.yaml:77). No test for the missing-param case (Spring's default 400 vs. our `Error` schema).
- No IT covering breaks (only `SlotMathTest` does — the `AvailabilityService → SlotMath` glue for breaks isn't exercised end-to-end).
- No IT covering owner-zone vs. client-zone date grouping (only unit). The cross-day grouping bug class is the most likely real-world failure mode and lives in production code, not in `SlotMath`.

## 4. The `Error` response schema (openapi.yaml:322) is never validated

The spec types every non-200 response as `{ code, message }`. The plan called for `ApiExceptionHandler` (§6); it doesn't exist. The service throws `ResponseStatusException`, which Spring renders as its default `{ timestamp, status, error, path }` body — **not** the contract's shape. Existing ITs only assert `status().isBadRequest()` / `isNotFound()`; none read the body. So the API is silently violating its own contract on every error, and the tests can't catch it.

## 5. Quality issues in tests that *do* exist

- **`AvailableSlotsControllerIT.happyPath_returns14DayWindowOfSlots:47`** uses `assertThat(body).hasSizeBetween(8, 10)`. With `FixedClockTestConfig.FIXED_NOW = 2026-05-04T05:00:00Z` (Mon, 07:00 Berlin) and Mon–Fri working days over a 14-day window, the answer is **exactly 10 working days** (May 4–8, 11–15). A fixed clock should yield deterministic assertions; the fuzzy range hides a bug if grouping ever drops a day.
- **`SlotMathTest.dstSpringForward_noDuplicateInstants:178`** asserts the test's *own* premise (`from0200.equals(from0300)`) rather than verifying the algorithm output. The real check — "no slot's wall-clock time falls in the missing 02:00–03:00 hour" — isn't made; collapsing happens incidentally because of the `TreeSet` in `SlotMath:42`. If someone swaps to a `List`, the test still passes for the duplicate clause.
- **`SlotMathTest.dstFallBack_ambiguousPicksEarlierOffset:195`** generates a single 60-min slot from 02:00 to 03:00 on the fall-back day and asserts it lands at `00:00 UTC`. That's the *earlier* offset (CEST = +2). Fine — but on fall-back day, `02:00` Berlin is ambiguous and `ZonedDateTime.of` picks the earlier offset by default; the algorithm isn't doing anything special. A real fall-back test would generate slots at 02:30 and 03:00 and verify monotonic UTC ordering, since the naive approach can produce non-monotonic instants.
- **`AvailabilityServiceTest`** uses Mockito, but `eventTypeRepo`/`calendarConfigRepo`/`scheduledEventRepo` mocks bypass any JPA/Flyway concerns. Useful, but doesn't replace ITs — and there are no ITs for unknown-event-type or invalid-tz on the controller, so the HTTP-status mapping (`ResponseStatusException` → 404/400) is only verified at service level. *Note:* `AvailableSlotsControllerIT.unknownEventType_returns404` does cover one of these — but only one.
- **No test for `server.servlet.context-path: /api`.** MockMvc bypasses it, so all paths in ITs are `/calendar/...`. The frontend talks to `/api/calendar/...` (per `backend/CLAUDE.md` §"Frontend ↔ backend proxy"). A simple `TestRestTemplate`-based smoke test is needed to ensure the context-path actually wires up.

## 6. Spec rules that have no test home anywhere

| Rule (source) | Test? |
|---|---|
| Booking writes a UTC `utcDateStart` from `(date, time, guestTimezone)` (openapi.yaml:282-303 + 351-370) | none (endpoint missing) |
| Window enforced **in guest's zone**, not server zone (task.txt:17-18) | none for booking; only slot-listing has cross-zone tests |
| Slot-alignment validation on POST (plan §5 step 8) | none |
| Past-slot rejection on POST | none |
| ID prefixes (`et_…`, `se_…`) | none |
| Validation: `eventTypeName`/`description`/`durationMinutes` required (openapi.yaml:268-281) | none |
| ScheduledEvent listing returns ordered "upcoming" view (task.txt:6) | none |

## Recommended next steps

1. **Implement the three `notImplemented()` endpoints first** — without them the spec is unfulfilled and tests are moot.
2. **Add `ApiExceptionHandler`** mapping all error paths to the generated `Error` DTO, then **assert response *bodies*** in ITs, not just statuses.
3. **Write `ConcurrencyIT`** that races two `POST /scheduled_events` against the same slot and asserts exactly one 200, one 409 with `code:409`.
4. **Write `EventTypeControllerIT`** covering create + list + validation rejections.
5. **Tighten the existing `hasSizeBetween(8, 10)` to `hasSize(10)`** and add an explicit assertion that May 9 and 10 are absent (already done in `weekendsHaveNoSlots`, but the count assertion should be exact).
6. **Strengthen the DST tests** to assert no slot's wall-clock falls in the missing hour (spring-forward), and to assert UTC monotonicity across the fall-back boundary (rather than equality of two precomputed instants).

The slot-generation core (`SlotMath` + 13 unit tests) is genuinely the strongest part of what Codex delivered. Everything around it — the booking lifecycle, error contract, validation, and end-to-end integration — is largely missing.
