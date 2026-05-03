# Phase 5 — Cleanup & polish (Tactical Plan)

Companion to [backend/CLAUDE.md](../CLAUDE.md). Picks up the deferred polish items called out in [REVIEW3.md](REVIEW3.md) (§3 `EventTypeControllerIT`, §5 `hasSizeBetween → hasSize` and DST hardening) and the operational surface CLAUDE.md §11.10 sketches (actuator health, `backend/README.md`). Phase 4 ([STEP4.md](STEP4.md)) is the precondition.

## Context

Phase 4 closed the booking lifecycle and the typed `Error` body — every `CalendarApi` method now has a real implementation, every error path emits `{code, message}`, and the EXCLUDE-GIST guard is exercised by both a controller-level cross-event-type collision test and a thread-racing `ConcurrencyIT`. What remained were the polish items REVIEW3 §3/§5 named and the operational surface CLAUDE.md §11.10 sketches: zero IT coverage on the two oldest endpoints (`POST` / `GET /event_types`), a fuzzy `hasSizeBetween(8, 10)` slot-count assertion, two DST tests that pin `java.time` rather than the algorithm, no `backend/README.md`, and no health endpoint. Phase 5 closes those gaps so the backend is shippable.

Frontend wiring (vite proxy swap, `dev:mock` split) is **deferred to a separate PR** per `backend/CLAUDE.md` §9. The Flyway-checksum reset documented in STEP4 deviation 5 is **not** in scope — it only affected a single local developer environment and is no longer an active problem.

## Goal

In one commit, land the deferred test polish (`EventTypeControllerIT`, `AvailableSlotsControllerIT` tightenings, `SlotMathTest` DST hardening), add `spring-boot-starter-actuator` exposing only `/health`, and write `backend/README.md` documenting `bootRun`, the `/api` context-path, the test command, and the health URL.

## Exit criteria (all must hold)

1. `./gradlew clean build` is `BUILD SUCCESSFUL` from `backend/`. Test count grows from Phase 4's 36 to ~44 (≥6 new `EventTypeControllerIT` + 2 new `SlotMathTest` DST methods; tightening of existing assertions doesn't change the count).
2. New file `backend/src/test/java/com/hexlet/calendar/web/EventTypeControllerIT.java` exists, `@Transactional`, extends `AbstractIntegrationTest`, with at least these `@Test` methods:
   - `happyPath_postReturns200WithGeneratedId` — POST a valid body; assert 200 and `$.eventTypeId` matches `^et_intro-call_[a-z0-9]{6}$`.
   - `missingRequiredField_returns400ErrorBody` — POST `{}`; assert 400, `$.code=400`, `$.message` references `eventTypeName`.
   - `malformedJson_returns400ErrorBody` — POST `"{not json"`; 400 with `$.message` containing `"Malformed"`.
   - `negativeDuration_returns400ErrorBody` — POST `durationMinutes=-5`; 400 with `$.code=400`. Confirms Bean Validation rejects before the DB CHECK.
   - `listEmpty_returns200EmptyArray` — GET; 200 with `$.length()=0`.
   - `listAfterCreate_orderedByCreatedAtAscending` — POST three event types in sequence (sleep ≥5 ms between to break `now()` ties); GET; assert names appear in insertion order. Anchors the `Sort.by("createdAt")` in `EventTypeService.list()`.
3. `AvailableSlotsControllerIT.happyPath_returns14DayWindowOfSlots` line 47 reads `assertThat(body).hasSize(10);` exactly. (FIXED_NOW = 2026-05-04 Mon Berlin → 14-day window covers Mon 5/4–Sun 5/17 → 10 working days after removing 5/9, 5/10, 5/16, 5/17.)
4. `AvailableSlotsControllerIT.unknownEventType_returns404` and `invalidClientTimeZone_returns400` each additionally assert `jsonPath("$.code")` and `jsonPath("$.message").exists()` on the response body.
5. `SlotMathTest` gains two new tests, both kept **alongside** the existing DST tests (which stay as no-regression checks):
   - `dstSpringForward_skipsMissingHourLocal` — for every generated `Instant`, assert `i.atZone(BERLIN).toLocalTime()` is **not** in `[02:00, 03:00)` on 2026-03-29.
   - `dstFallBack_instantsStrictlyMonotonic` — generate slots at owner-local 02:00, 02:30, 03:00, 03:30 across the fall-back boundary on 2026-10-25 with 30-min step; assert the resulting `List<Instant>` is strictly monotonically increasing.
6. `spring-boot-starter-actuator` is on the runtime classpath; `application.yml` exposes only `health`; `curl http://localhost:8080/api/actuator/health` returns `{"status":"UP"}` after `./gradlew bootRun`.
7. `backend/README.md` exists, ≤120 lines, covering: prerequisites (Docker, JDK 21), `./gradlew bootRun`, `./gradlew clean build`, `/api` context-path note, the health URL.
8. **No production source files under `service/`, `web/` (except `application.yml`), `domain/`, `mapping/`, `time/`, `config/` are modified.** No new Flyway migrations. No `compose.yaml` changes. No `application-test.yml` changes. No frontend changes.

---

## Architecture decisions

### A. DST tests added alongside existing ones, not replacing them

REVIEW3 §5 is right that `dstSpringForward_noDuplicateInstants` (line 178-192) and `dstFallBack_ambiguousPicksEarlierOffset` (line 195-204) pin `java.time` behaviour rather than `SlotMath` output. But the existing tests are still cheap no-regression signals — if `SlotMath` ever returns duplicates or drops the `TreeSet`, they fail. The new tests target the actual algorithm contract: "no slot's wall-clock falls in the missing hour" (spring) and "UTC instants stay monotonic across the duplicated hour" (fall-back). Keep both pairs.

### B. Actuator on the same port, under `/api/actuator/health`

The default mapping with `server.servlet.context-path: /api` routes actuator to `/api/actuator/*`. A separate `management.server.port` adds a port to remember and zero security benefit in this no-auth project. Restrict exposure to `health` only and keep `show-details: never` (Spring's default for unauthenticated traffic) so we don't leak DB connection state. The README must say `/api/actuator/health` explicitly — guests hitting `/actuator/health` without the prefix will 404 and assume actuator is broken.

### C. `EventTypeControllerIT` mirrors the existing IT pattern

Class-level `@Transactional` (rolls back per test), `extends AbstractIntegrationTest` (inherits MockMvc, ObjectMapper, repos, `FixedClockTestConfig`, the `jdbc:tc:` test datasource). No `@Container`. No new helpers. Minimal divergence from `AvailableSlotsControllerIT` and `ErrorMappingIT`.

### D. Slug-pattern assertion uses an anchored regex

`IdGenerator.forEventType("Intro Call")` produces `et_intro-call_<6 lowercase-alphanumeric>` per `IdGenerator.slugify` (lines 22-33: lowercase, runs of non-alphanumerics → single `-`, leading/trailing `-` stripped, capped at 32 chars). The test asserts `Pattern.matches("^et_intro-call_[a-z0-9]{6}$", id)` — tight enough to catch a slug regression, loose enough to tolerate the random suffix.

### E. `createdAt` ties broken with `Thread.sleep(5)` between POSTs in the ordering test

V1 uses `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`. Postgres `now()` returns the **transaction start** timestamp, so three POSTs that each open their own transaction may still land on identical microseconds in fast CI. A 5 ms sleep between calls forces distinct `now()` values. Slightly ugly; explicit and reliable. Alternative — assert "set equality of names" — loses the sort-direction signal. Pay the 15 ms.

### F. `negativeDuration_returns400` asserts at the controller, not the DB

The DTO's `@Positive` annotation (Bean Validation, generated from `openapi.yaml` `durationMinutes: minimum: 1`) rejects with `MethodArgumentNotValidException` → 400 via `ApiExceptionHandler.handleValidation`. The DB CHECK constraint (`duration_minutes > 0`, V1 line 7) is the second line of defence; if Bean Validation regresses, the CHECK still fires but as a 500 from `DataIntegrityViolationException`. The test pins the contract surface — 400, not 500.

---

## Step-by-step file changes

### Step 1 — `backend/build.gradle.kts` (modify)

Add one line under existing starters (around line 22):

```kotlin
implementation("org.springframework.boot:spring-boot-starter-actuator")
```

Verify with `./gradlew dependencies --configuration runtimeClasspath | grep actuator`.

### Step 2 — `backend/src/main/resources/application.yml` (modify)

Append (file currently ends at line 31, `owner.timezone: Europe/Berlin`):

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: never
```

`include: health` is the entire allowlist — `/actuator/info`, `/actuator/metrics`, etc. stay disabled.

### Step 3 — `backend/src/test/java/com/hexlet/calendar/web/EventTypeControllerIT.java` (new)

Pattern:

```java
@Transactional
class EventTypeControllerIT extends AbstractIntegrationTest {

    @Test
    void happyPath_postReturns200WithGeneratedId() throws Exception {
        String body = """
            {"eventTypeName":"Intro Call","description":"Short chat","durationMinutes":30}""";
        mockMvc.perform(post("/calendar/event_types").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventTypeId", matchesPattern("^et_intro-call_[a-z0-9]{6}$")))
            .andExpect(jsonPath("$.eventTypeName").value("Intro Call"))
            .andExpect(jsonPath("$.durationMinutes").value(30));
    }
    // … plus the 5 other tests from exit criterion 2
}
```

Use `org.hamcrest.Matchers.matchesPattern` for the regex assertion (already on the test classpath via Spring Boot test starter).

### Step 4 — `backend/src/test/java/com/hexlet/calendar/web/AvailableSlotsControllerIT.java` (modify)

- Line 47: `hasSizeBetween(8, 10)` → `hasSize(10)`. Add a one-line comment referencing `FixedClockTestConfig.FIXED_NOW = 2026-05-04T05:00:00Z` (Mon Berlin) → 10 working days in the 14-day window.
- Lines 87-94 (`invalidClientTimeZone_returns400`): append two assertions — `.andExpect(jsonPath("$.code").value(400))` and `.andExpect(jsonPath("$.message").exists())`.
- Lines 97-101 (`unknownEventType_returns404`): append the equivalent for code 404.
- Add static import `org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath` if not already present.

### Step 5 — `backend/src/test/java/com/hexlet/calendar/time/SlotMathTest.java` (modify, append two tests)

Append after the existing `dstFallBack_ambiguousPicksEarlierOffset`:

- `dstSpringForward_skipsMissingHourLocal` — same setup as line 178-184 (Sunday-only, BERLIN, 2026-03-29 spring-forward, 30-min slots from 01:30 to 04:00). Assert:
  ```java
  for (Instant i : slots) {
      LocalTime lt = i.atZone(BERLIN).toLocalTime();
      assertThat(lt).isNotBetween(LocalTime.of(2, 0), LocalTime.of(3, 0));
  }
  ```
  Use `isNotBetween` with the half-open `[02:00, 03:00)` semantics — AssertJ's `isBetween` is inclusive on both ends, so use a stricter custom check or build the assertion with two predicates.
- `dstFallBack_instantsStrictlyMonotonic` — Sunday-only, BERLIN, 2026-10-25 (fall-back), 30-min slots covering owner-local 02:00–04:00 (i.e. `startOfDay=02:00, endOfDay=04:00`). Generate four slots: 02:00, 02:30, 03:00, 03:30 in owner-local. Assert:
  ```java
  assertThat(slots).hasSize(4).isSortedAccordingTo(Comparator.naturalOrder());
  for (int i = 1; i < slots.size(); i++) {
      assertThat(slots.get(i)).isAfter(slots.get(i - 1));
  }
  ```
  Strict monotonicity catches a hypothetical regression where the algorithm picks the later offset for ambiguous wall times — the resulting UTC instants would zig-zag.

### Step 6 — `backend/README.md` (new)

Suggested sections, ≤120 lines total, no emojis:

```
# Calendar Backend

Spring Boot 3.4 service that fulfils the OpenAPI contract in `spec/openapi.yaml`,
backed by PostgreSQL 16 via Flyway migrations.

## Prerequisites
- Docker (for the local Postgres container)
- JDK 21

## Run
    ./gradlew bootRun

Spring Boot's Docker Compose support (compose.yaml) starts Postgres on :5432,
waits for the healthcheck, then boots the app on :8080. Flyway applies V1+V2
on first start.

## Test
    ./gradlew clean build

Testcontainers spins up postgres:16-alpine for the integration phase. No
local Postgres needed for tests.

## Endpoints
Controllers serve OpenAPI paths under `server.servlet.context-path: /api`.
- POST /api/calendar/event_types
- GET  /api/calendar/event_types
- POST /api/calendar/scheduled_events
- GET  /api/calendar/scheduled_events
- GET  /api/calendar/scheduled_events/{id}
- GET  /api/calendar/event_types/{id}/available_slots?clientTimeZone=...

## Health
    curl http://localhost:8080/api/actuator/health

Only the `health` actuator endpoint is exposed; details are hidden.

## Layout
- `spec/openapi.yaml`            — contract source; generated DTOs + interfaces land in `build/generated/openapi/`.
- `src/main/resources/db/migration/` — Flyway migrations.
- `src/main/java/com/hexlet/calendar/` — application code.
- `docs/STEP{1..4}.md`           — phase-by-phase implementation history.
```

---

## Risks to watch

1. **`hasSize(10)` rigidity.** If `FixedClockTestConfig.FIXED_NOW` is ever changed (e.g. to a Friday or a DST week), this assertion fails. The mitigation is the inline comment referencing the constant — anyone changing the clock will see the breadcrumb.
2. **`createdAt` tie-breaking on fast CI.** If `listAfterCreate_orderedByCreatedAtAscending` ever flakes despite the 5 ms sleep, bump to 10 ms or rewrite to assert "names are a permutation of the seeded set" — but only after a real flake, not preemptively.
3. **Slug regex coupling to `IdGenerator` internals.** The anchored pattern `^et_intro-call_[a-z0-9]{6}$` will break if `IdGenerator.slugify` ever changes its dash policy (e.g. switches to underscores). That's exactly the regression we want to catch — the test is *meant* to fail loudly. Don't soften it.
4. **Actuator dependency widening 5xx responses to default Spring `/error`.** Low risk: actuator's `/actuator/*` mapping is namespaced, and `ApiExceptionHandler` (`@RestControllerAdvice`) still wins for app endpoints. Re-run `ErrorMappingIT` after the dep is added to confirm no `Error` body shape regressed.
5. **Bean Validation `@Positive` may not be auto-applied to generated DTOs.** The OpenAPI generator emits validation annotations only when `performBeanValidation: true` is set (it is, per `build.gradle.kts:64`). If `negativeDuration_returns400` returns 200 instead of 400, that flag is wrong. Verify by reading the generated `CreateEventType.java` after `./gradlew clean compileJava`.
6. **`SlotMathTest.dstFallBack_instantsStrictlyMonotonic` algorithm coupling.** The assertion depends on `SlotMath` resolving ambiguous wall times to the **earlier** offset (CLAUDE.md §4 step 6 — Java's `ZonedDateTime.of` default). If `SlotMath` is ever rewritten with `withLaterOffsetSameLocal`, this test fails — exactly as intended.

---

## Verify (after `./gradlew bootRun`)

```bash
cd backend
./gradlew clean build
# expect: BUILD SUCCESSFUL, ~44 tests, including 6 new EventTypeControllerIT and 2 new SlotMathTest DST methods.

./gradlew bootRun &
# Compose starts Postgres, Flyway applies V1+V2, app boots on :8080.

curl -sS http://localhost:8080/api/actuator/health
# expect: {"status":"UP"}

curl -sS http://localhost:8080/api/calendar/event_types
# expect: []

curl -sS -X POST http://localhost:8080/api/calendar/event_types \
  -H 'Content-Type: application/json' \
  -d '{"eventTypeName":"Intro Call","description":"Short chat","durationMinutes":30}' | jq .
# expect: {"eventTypeId":"et_intro-call_<6 chars>", "eventTypeName":"Intro Call", ...}

curl -sS -X POST http://localhost:8080/api/calendar/event_types \
  -H 'Content-Type: application/json' -d '{"eventTypeName":"Bad","description":"d","durationMinutes":-1}' | jq .
# expect: {"code":400,"message":"...durationMinutes..."}
```

---

## Critical files to be created or modified

**Created:**
- `backend/src/test/java/com/hexlet/calendar/web/EventTypeControllerIT.java`
- `backend/README.md`

**Modified:**
- `backend/build.gradle.kts` — add `spring-boot-starter-actuator`.
- `backend/src/main/resources/application.yml` — append `management:` block exposing only `health`.
- `backend/src/test/java/com/hexlet/calendar/web/AvailableSlotsControllerIT.java` — `hasSize(10)`, add `$.code` / `$.message` assertions to two error tests.
- `backend/src/test/java/com/hexlet/calendar/time/SlotMathTest.java` — append two DST tests.

**NOT touched:**
- All `backend/src/main/java/.../service/`, `web/error/`, `web/CalendarController.java`, `domain/`, `mapping/`, `time/SlotMath.java`, `config/` source.
- All Phase-3 / Phase-4 ITs other than the surgical edits above.
- `compose.yaml`, `application-test.yml`, V1__init.sql, V2__seed_owner_calendar.sql, generator config.
- `frontend/` — deferred to its own PR per `backend/CLAUDE.md` §9.

---

## Commit plan

One commit, matching prior phases' single-bundle cadence:

- `feat(backend): EventTypeControllerIT, DST hardening, actuator health, README (phase 5)`

---

## Hand-off to a possible Phase 6

Phase 5 closes REVIEW3's deferred items and CLAUDE.md §11.10. After it lands, the only known un-shipped pieces of CLAUDE.md are:

1. **Frontend wiring** (CLAUDE.md §9). Separate PR. `frontend/vite.config.ts` target swap from Prism (`:4010`) to Spring (`:8080`) and removal of the `/api` rewrite; `frontend/package.json` keeps `dev:mock` for the Prism workflow and adds `dev` for the real backend.
2. **Optional request logging filter** (CLAUDE.md §11.10). Not justified yet — `logging.level.org.springframework.web=DEBUG` covers the local debugging case without the maintenance cost of an `OncePerRequestFilter`.
3. **End-to-end frontend smoke** of the full guest + owner flows once the proxy points at the real backend (CLAUDE.md §12 step 8).

---

## Post-run notes (what actually shipped)

`./gradlew clean build` is `BUILD SUCCESSFUL`. 44 tests green: 15 `SlotMathTest` (13 → 15 with the two new DST tests), 3 `AvailabilityServiceTest`, 5 `AvailableSlotsControllerIT`, 9 `ScheduledEventControllerIT`, 1 `ConcurrencyIT`, 5 `ErrorMappingIT`, 6 `EventTypeControllerIT` (new). Exit criterion 1 (~44) hit on the nose.

Files created/modified match the plan's "Critical files" list, with one forced addition documented below.

### Deviation 1 — `EventTypeService.create()` got a manual `durationMinutes < 1` check

The plan's Architecture §F and exit criterion 2 (`negativeDuration_returns400ErrorBody`) assumed the OpenAPI generator would emit `@Positive` (or `@Min(1)`) on `CreateEventType.durationMinutes` because of a `minimum: 1` constraint in `spec/openapi.yaml`. Risk #5 explicitly told us to verify that by reading the generated DTO.

Verification result: `spec/openapi.yaml:279-281` does **not** declare `minimum: 1` for `durationMinutes`, so the generated `CreateEventType` DTO carries only `@NotNull`. A negative value would slip past Bean Validation, hit the V1 `CHECK (duration_minutes > 0)` constraint, and surface as `DataIntegrityViolationException` with SQLSTATE `23514`. The current `ApiExceptionHandler.handleDataIntegrity` only special-cases `23P01` (the EXCLUDE-GIST exclusion) — `23514` falls through to the 500 branch. Without intervention, `negativeDuration_returns400ErrorBody` would return 500.

Two repair paths considered:
1. **Add `minimum: 1` to `spec/openapi.yaml`** — cleanest fit with the plan's wording, but the spec drives both backend and frontend codegen and was excluded from edits in this phase.
2. **Add validation in the service bean** — costs one `if`-block in `EventTypeService.create` (a `service/` file, which exit criterion 8 listed as untouchable) but keeps the spec, generator config, and DB layer alone.

Took path 2 at the user's direction. The added check throws `BadRequestException("durationMinutes: must be greater than or equal to 1")`, which `ApiExceptionHandler` already maps to a typed 400 `Error` body. The DB CHECK constraint stays as the second line of defence. Exit criterion 8 is therefore violated by exactly one production file (`service/EventTypeService.java`), with the trade-off explicitly recorded here.

### Hand-off updates for a possible Phase 6

- The duration-minimum is currently enforced in three places: the OpenAPI `@NotNull` (presence only), `EventTypeService.create` (the new manual check), and the V1 DB `CHECK`. If Phase 6 ever reopens the spec, adding `minimum: 1` to `CreateEventType.durationMinutes` would make the manual service check redundant — delete it then to keep one source of truth.
- The actuator endpoint lives at `http://localhost:8080/api/actuator/health` (the `/api` prefix from `server.servlet.context-path` applies). README documents this; anyone hitting `/actuator/health` without the prefix will 404 and assume actuator is broken.
- `dstFallBack_instantsStrictlyMonotonic` filters by Berlin-local date before counting (`hasSize(4)`) because the Sunday-only working-day setup in the test produces slots on the next Sunday too (within the 14-day window). The filter is what makes the four-slot count correct.
- Local dev requires Docker to be running before `./gradlew clean build` — Testcontainers exits with `DockerClientProviderStrategy` errors otherwise. (Hit this once during the Phase 5 build.)
