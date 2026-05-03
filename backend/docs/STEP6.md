# Phase 6 — Single deployable image (Tactical Plan)

Companion to [backend/CLAUDE.md](../CLAUDE.md). Out of scope for CLAUDE.md as written — this is a forward-looking phase. Phase 5 ([STEP5.md](STEP5.md)) is in: actuator health is live at `/api/actuator/health`, `backend/README.md` exists, build is `BUILD SUCCESSFUL` with 44 tests. The frontend vite-proxy PR is **not** a precondition — production serves the React app from Spring's static resources; dev-mode vite proxy is irrelevant outside development.

## Context

After Phase 5 the backend is feature-complete and shippable in isolation, but deployment still requires standing up two artefacts independently — the Spring Boot JAR plus a separately-built React `dist/` served by some other process. The user wants a single deployable Docker image carrying both, so a `docker run` against the right env vars yields a working app at one port. The `/api` context-path decision in CLAUDE.md (`server.servlet.context-path: /api`) makes this clean: React serves from `/`, API from `/api/*`, no reverse proxy needed in production. Dev-mode vite proxy stays as the development-time concern it already is.

## Goal

Produce one Docker image that contains the Spring Boot fat JAR with the built React `dist/` baked in as static resources. A multi-stage Dockerfile builds frontend (Node), then backend (Gradle, with the frontend dist copied into `src/main/resources/static/` before `bootJar`), then ships a minimal JRE runtime image. SPA client-side routes resolve to `index.html` via a Spring forward; API calls hit controllers under `/api`; `/api/actuator/health` powers the Docker `HEALTHCHECK`.

## Resolved decisions

1. **Frontend build orchestration** → plain multi-stage Dockerfile. Docker is the only place the two builds are joined; local `./gradlew bootJar` does not bundle frontend. Keeps the Java build free of npm dependencies and matches the existing local dev workflow (`./gradlew bootRun` + `npm run dev` in two terminals). Gradle node plugin rejected — extra build complexity for a workflow nobody asked for.
2. **SPA client-side routing** → Spring forwarding controller. Clean URLs, ~10 lines of Java, the standard Spring Boot SPA pattern. HashRouter rejected — ugly URLs and the frontend uses `BrowserRouter`.
3. **Runtime base image** → `eclipse-temurin:21-jre-alpine`. Best size/familiarity tradeoff (~70 MB base). Distroless reconsidered only if a future security review demands it.

## Exit criteria (all must hold)

1. `docker build -t calendar-app .` from repo root succeeds. Final image size ≤120 MB (alpine + JRE + JAR).
2. `docker run --rm -p 8080:8080 -e SPRING_DATASOURCE_URL=… -e SPRING_DATASOURCE_USERNAME=… -e SPRING_DATASOURCE_PASSWORD=… calendar-app` boots successfully and Flyway applies V1+V2 against the configured Postgres.
3. `curl http://localhost:8080/` returns the React `index.html` (HTTP 200, `Content-Type: text/html`).
4. `curl http://localhost:8080/admin/event-types` and `curl http://localhost:8080/book/et_intro-call_abc123` each return `index.html` (forwarded by the SPA fallback) — not 404. (These match the actual frontend routes in `frontend/src/routes/index.tsx`.)
5. `curl http://localhost:8080/assets/<some-vite-hashed-asset>.js` returns the JS asset directly with `Content-Type: application/javascript` — the SPA fallback does not swallow real static files.
6. `curl http://localhost:8080/api/calendar/event_types` returns `[]` (or the seeded list); `curl http://localhost:8080/api/actuator/health` returns `{"status":"UP"}`.
7. The Docker `HEALTHCHECK` directive uses `/api/actuator/health` and reports `healthy` within 30 s of container start.
8. `compose.prod.yaml` (new, separate from the existing dev `compose.yaml`) brings up Postgres + the built app image with appropriate env-var wiring. `cp .env.example .env && <edit POSTGRES_PASSWORD> && docker compose -f compose.prod.yaml up -d --build` produces a working end-to-end stack with both services reporting `(healthy)` in `docker compose ps`. Postgres is **not** exposed on the host (no `ports:` on the postgres service); only the app's port is reachable.
9. `application.yml`'s datasource block accepts env-var overrides (`SPRING_DATASOURCE_URL` etc.) without code changes — Spring Boot's relaxed binding handles this for free, but the README must document it.
10. `backend/README.md` (created in Phase 5) gains a "Production image" section with build + run examples.
11. `./gradlew clean build` stays `BUILD SUCCESSFUL`. Test count rises from STEP5's 44 → 48 (4 new `SpaFallbackControllerIT` methods).
12. **Not changed by Phase 6**: `application.yml`, all `src/main/java/**` source except the single new `SpaFallbackController`, all existing tests, all Flyway migrations, the dev `compose.yaml`, the OpenAPI spec, and `frontend/`.

---

## Architecture decisions

### A. Frontend bundled into the Spring Boot JAR as static resources

Spring Boot's `WebMvcAutoConfiguration` serves anything in `classpath:/static/`, `classpath:/public/`, `classpath:/resources/`, or `classpath:/META-INF/resources/` from the application root. Copying Vite's `dist/` into `backend/src/main/resources/static/` during the image build means a single JAR carries both halves; one process, one port, zero reverse-proxy plumbing in production. The `/api` context-path keeps frontend (`/`) and API (`/api/*`) on disjoint URL spaces — no path collisions.

This is **not** done in `src/` directly: copying `dist/` into `src/main/resources/static/` happens inside the Docker stage so the Git working tree stays clean. Locally `./gradlew bootRun` continues to start the backend with no frontend, and devs run `npm run dev` separately (with the vite proxy targeting `:8080`, which is the deferred frontend PR).

### B. SPA fallback via a single forwarding controller

Spring Boot serves `/` → `index.html` automatically. But React Router paths like `/book/et_xyz` and `/admin/event-types` resolve to a 404 from Spring's static resource handler because no `book` or `admin` directory exists in `static/`. Standard fix:

```java
@Controller
class SpaFallbackController {
    @GetMapping(value = {"/{path:[^.]*}", "/**/{path:[^.]*}"})
    public String forward() {
        return "forward:/index.html";
    }
}
```

The constraint `[^.]*` is on the **last** segment of each pattern: pattern 1 covers single-segment paths (`/foo`), pattern 2 covers any path whose last segment has no dot (`/admin/event-types`, `/book/et_intro-call_abc123`). Critically, `/assets/index-abc123.js` does **not** match either pattern because the last segment contains a dot — it falls through to Spring's static resource handler. Frontend routes (`/`, `/book/:eventTypeId`, `/admin/event-types`, `/admin/bookings`) are at most two segments deep, so the patterns cover everything React Router needs.

`/api/*` is also not caught because controllers under `CalendarApi` win the route table by mapping specificity. ~12 lines of code, no config flags.

> **Note on the earlier regex draft.** A common variant (`/{path:[^.]*}/**`) puts the no-dot constraint on the *first* segment with `**` trailing. That is wrong here: `**` matches anything including dots, so `/assets/foo.js` would forward to index.html and break exit criterion 5. The corrected pattern places the constraint on the last segment.

Alternative considered: `WebMvcConfigurer.addViewControllers(registry)` — works the same way but is harder to make path-pattern-aware. The controller is clearer.

### C. Multi-stage Dockerfile lives at repo root

Build context must include both `frontend/` and `backend/`, so the `Dockerfile` lives at `/` (one level above each project). A repo-root `.dockerignore` excludes `node_modules/`, `**/build/`, `**/.gradle/`, `.git/`, `frontend/dist/` (rebuilt fresh), etc. — keeps the build context small.

Rejected: putting the Dockerfile inside `backend/` and using `..` paths or extra mount tricks. Cross-project builds belong at the project's natural common ancestor.

### D. Production datasource via env-vars; `application.yml` documents but doesn't hardcode

Spring Boot's relaxed binding maps `SPRING_DATASOURCE_URL` → `spring.datasource.url` automatically. `application.yml` keeps the dev defaults (`jdbc:postgresql://localhost:5432/calendar`) so `./gradlew bootRun` keeps working as today; production overrides via env. No new `application-prod.yml` profile needed — fewer moving parts.

### E. `compose.prod.yaml` is separate from `compose.yaml`

Existing `compose.yaml` is consumed by Spring Boot Docker Compose support during `bootRun` and only brings up Postgres. A new `compose.prod.yaml` brings up Postgres **plus** the app image, with the app waiting on Postgres' healthcheck. Separate file because the dev compose is auto-managed by Spring Boot's plugin and would conflict with also defining the app there.

### F. Healthcheck uses `/api/actuator/health` (Phase 5 dependency)

```Dockerfile
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/actuator/health | grep -q '"status":"UP"' || exit 1
```

`wget` is on alpine by default (`apk add` not needed). `start-period=30s` covers JVM startup + Flyway migrations on a cold container. Without Phase 5's actuator, Phase 6 would need to invent a health URL — better to depend on it cleanly.

---

## Step-by-step file changes

### Step 1 — `Dockerfile` at repo root (new)

```dockerfile
# syntax=docker/dockerfile:1.7

FROM node:20-alpine AS frontend-builder
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS backend-builder
WORKDIR /app/backend
# Copy gradle wrapper first for layer caching
COPY backend/gradlew backend/gradlew.bat ./
COPY backend/gradle/ ./gradle/
COPY backend/build.gradle.kts backend/settings.gradle.kts ./
# Pre-fetch dependencies (cached unless build files change)
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
# Copy sources + spec + the freshly-built frontend dist as static resources
COPY backend/spec/ ./spec/
COPY backend/src/ ./src/
COPY --from=frontend-builder /app/frontend/dist/ ./src/main/resources/static/
RUN ./gradlew --no-daemon clean bootJar -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Non-root user for runtime hardening
RUN addgroup -S app && adduser -S app -G app
COPY --from=backend-builder --chown=app:app /app/backend/build/libs/*.jar /app/app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

Note: `-x test` skips tests during image build (CI runs them separately). For a self-contained image build that includes tests, drop `-x test` — but image build time roughly doubles.

### Step 2 — `.dockerignore` at repo root (new)

```
.git
.gitignore
.gradle
**/.gradle
**/build
**/node_modules
**/dist
.DS_Store
*.iml
.idea
.vscode
*.md
!backend/spec/**
```

Whitelisting `backend/spec/**` re-includes the OpenAPI spec which the OpenAPI generator consumes during `compileJava`.

### Step 3 — `backend/src/main/java/com/hexlet/calendar/web/SpaFallbackController.java` (new)

```java
package com.hexlet.calendar.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class SpaFallbackController {

    @GetMapping(value = {"/{path:[^.]*}", "/**/{path:[^.]*}"})
    String forward() {
        return "forward:/index.html";
    }
}
```

Package matches the existing `CalendarController` (`com.hexlet.calendar.web`). Package-private class — not part of the public API.

### Step 4 — `compose.prod.yaml` at repo root (new) + `.env.example`

Production-ready compose: restart policies, no host-exposed Postgres port (only the app is reachable), credentials read from a `.env` file at deploy time, named network for isolation, and resource hints.

```yaml
name: calendar

services:
  postgres:
    image: postgres:16-alpine
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-calendar}
      POSTGRES_USER: ${POSTGRES_USER:-calendar}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}
    volumes:
      - calendar-pgdata-prod:/var/lib/postgresql/data
    networks:
      - calendar-net
    # No `ports:` block — Postgres is reachable only on the internal network.
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-calendar} -d ${POSTGRES_DB:-calendar}"]
      interval: 10s
      timeout: 3s
      retries: 10
      start_period: 10s

  app:
    build:
      context: .
      dockerfile: Dockerfile
    image: calendar-app:${APP_VERSION:-local}
    restart: unless-stopped
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:-calendar}
      SPRING_DATASOURCE_USERNAME: ${POSTGRES_USER:-calendar}
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}
      SPRING_DOCKER_COMPOSE_ENABLED: "false"  # in-app plugin must NOT try to manage compose from inside the container
      JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"
    ports:
      - "${APP_PORT:-8080}:8080"
    networks:
      - calendar-net
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/api/actuator/health"]
      interval: 10s
      timeout: 3s
      retries: 3
      start_period: 30s

networks:
  calendar-net:
    driver: bridge

volumes:
  calendar-pgdata-prod:
```

Companion `.env.example` at repo root (also new), to be copied to `.env` and edited per environment:

```
# Database
POSTGRES_DB=calendar
POSTGRES_USER=calendar
POSTGRES_PASSWORD=replace-me-with-a-strong-secret

# App
APP_PORT=8080
APP_VERSION=local
```

**Add `.env` to the repo-root `.gitignore`** to keep real credentials out of version control. The current root `.gitignore` does not have it; append `.env` so a careless `git add .` after editing local credentials doesn't leak the password. `.env.example` is committed.

Production-readiness rationale per knob:
- `restart: unless-stopped` — survives Docker daemon restarts; doesn't restart on explicit `docker compose down`.
- No `ports:` on `postgres` — DB is only reachable through the app, no host-port surface.
- `${VAR:?msg}` — fails fast if `POSTGRES_PASSWORD` isn't set, instead of silently using an empty password.
- `${VAR:-default}` — defaults for non-secret knobs (db name, user, port).
- Named `calendar-net` — explicit network keeps service-to-service DNS predictable across compose project renames.
- Both services have healthchecks — `docker compose ps` shows real readiness, `depends_on … service_healthy` is enforceable.
- `JAVA_TOOL_OPTIONS` — JDK 21 already honours container memory limits, but the explicit flag is a good signal and lets ops tune `MaxRAMPercentage` per host.
- `SPRING_DOCKER_COMPOSE_ENABLED=false` — Spring Boot's Docker Compose support would otherwise try to manage compose from inside the container, which both wrong and impossible.

Deploy workflow:

```
cp .env.example .env
# edit .env, set POSTGRES_PASSWORD
docker compose -f compose.prod.yaml up -d --build
docker compose -f compose.prod.yaml logs -f app   # tail until "Started CalendarApplication"
docker compose -f compose.prod.yaml ps            # both services should report (healthy)
```

### Step 5 — `backend/README.md` (modify, add section)

Append after the existing "Health" section:

```
## Production image
A repo-root Dockerfile builds a single image carrying the React frontend + the Spring backend.

    docker build -t calendar-app .
    docker run --rm -p 8080:8080 \
      -e SPRING_DATASOURCE_URL=jdbc:postgresql://<db-host>:5432/calendar \
      -e SPRING_DATASOURCE_USERNAME=calendar \
      -e SPRING_DATASOURCE_PASSWORD=calendar \
      calendar-app

Or use the production compose file at the repo root (Postgres + app, no host-exposed DB port):

    cp .env.example .env
    # edit .env and set POSTGRES_PASSWORD
    docker compose -f compose.prod.yaml up -d --build
    docker compose -f compose.prod.yaml ps   # both services should report (healthy)

The frontend serves from /, the API from /api/*, and the health endpoint from /api/actuator/health.
```

Also bump the existing layout line `docs/STEP{1..5}.md` → `docs/STEP{1..6}.md` so the cross-reference stays accurate.

### Step 6 — `backend/src/test/java/com/hexlet/calendar/web/SpaFallbackControllerIT.java` (new)

Small `MockMvc` smoke test, anchoring the regex behaviour so a future "simplification" can't silently re-introduce the dotted-asset bug. Extends `AbstractIntegrationTest` for consistency with the other web ITs (no Testcontainers cost beyond what's already paid).

```java
class SpaFallbackControllerIT extends AbstractIntegrationTest {

    @Test
    void clientRoute_singleSegment_forwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/admin")).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void clientRoute_twoSegments_forwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/admin/event-types")).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/book/et_intro-call_abc123")).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void dottedAsset_isNotForwarded() throws Exception {
        // /assets/foo.js has a dot in the last segment — must NOT be forwarded
        // (would otherwise serve index.html as JS and break the SPA after deploy).
        mockMvc.perform(get("/assets/foo.js"))
            .andExpect(status().isNotFound())
            .andExpect(forwardedUrl(null));
    }

    @Test
    void apiPath_isNotForwarded_handledByCalendarController() throws Exception {
        // /calendar/event_types is the API; must hit CalendarController, not the SPA fallback.
        mockMvc.perform(get("/calendar/event_types")).andExpect(status().isOk());
    }
}
```

`AbstractIntegrationTest` sets the context-path to `/api`, so `MockMvc` paths are relative to `/api` here — `get("/calendar/event_types")` exercises `/api/calendar/event_types`. Test count rises from STEP5's 44 → 48.

### Step 7 — Verify the build context size

Before building, `du -sh $(git ls-files | head)` and `docker build --progress=plain` should agree the context excludes `node_modules`, `build/`, `.gradle/`. If the build context is >50 MB the `.dockerignore` is missing entries.

---

## Risks to watch

1. **Static resource caching headers.** Spring Boot serves `/` static resources without `Cache-Control` headers by default. Vite emits hashed asset filenames (`index-abc123.js`), so aggressive caching of `/assets/*` is safe — but `index.html` itself must never be cached (or the SPA serves stale entry points after deploys). Add `spring.web.resources.cache.cachecontrol.max-age` carefully or accept defaults; verify with `curl -I http://localhost:8080/index.html` returns no/short cache headers.
2. **SPA fallback regex eats real 404s.** A request to `/typo` (no dot) forwards to `index.html`, which means React Router shows its own 404 — invisible to the server's logs as a 404. Acceptable for an SPA, but worth noting in the README so backend logs don't mislead during debugging.
3. **`/error` and `/api/error`.** Spring's default error mapping at `/error` could conflict with the SPA fallback regex. Test by triggering a 500 against `/api/...` and confirming the typed `Error` body still emerges (not the React app). The `@RestControllerAdvice` should win, but verify.
4. **Build context bloat.** Without `.dockerignore`, `frontend/node_modules` (~300 MB) and `backend/build` (~50 MB) are copied into the build context, slowing every build. Confirm with `docker build --progress=plain` that the "transferring context" line stays under ~10 MB.
5. **Gradle cache invalidation across stages.** The Dockerfile's `RUN ./gradlew dependencies` step caches deps in `/root/.gradle/`, which is **not** carried across stages by default. For faster CI, mount a buildx cache (`RUN --mount=type=cache,target=/root/.gradle …`) — out of scope for this plan but flagged.
6. **`HEALTHCHECK` JVM warm-up.** First-boot Flyway + JPA bootstrap can exceed `start-period=30s` on slow hosts. If healthcheck flaps in CI, raise to 60 s rather than skip the healthcheck.
7. **Non-root user vs `/tmp` writes.** The non-root `app` user can `chmod`-write inside its `WORKDIR` but `java.io.tmpdir` defaults to `/tmp` which is world-writable. Spring Boot's embedded Tomcat uses `/tmp` for upload spool files. Should work; verify by exercising a multipart endpoint (none exist yet, so low risk).
8. **Postgres host networking on macOS.** `host.docker.internal` works for `docker run` on Mac/Win but not Linux — the README example must show the env-var, not the literal host. `compose.prod.yaml` uses the service name `postgres` which is portable.

---

## Verify

```bash
# From repo root
docker build -t calendar-app .

# Image size sanity
docker images calendar-app --format '{{.Size}}'   # expect ≤120 MB

# Standalone run with external Postgres (requires Phase 5 actuator)
docker run --rm -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/calendar \
  -e SPRING_DATASOURCE_USERNAME=calendar \
  -e SPRING_DATASOURCE_PASSWORD=calendar \
  --name calendar-app calendar-app &

sleep 30   # allow Flyway + warm-up

# Frontend
curl -sS -o /dev/null -w '%{http_code} %{content_type}\n' http://localhost:8080/
# expect: 200 text/html

# SPA fallback
curl -sS -o /dev/null -w '%{http_code} %{content_type}\n' http://localhost:8080/booking/abc
# expect: 200 text/html  (NOT 404)

# Static asset (should NOT be caught by SPA fallback — has a dot in the path)
curl -sS -o /dev/null -w '%{http_code} %{content_type}\n' http://localhost:8080/vite.svg
# expect: 200 image/svg+xml

# API
curl -sS http://localhost:8080/api/calendar/event_types         # []
curl -sS http://localhost:8080/api/actuator/health              # {"status":"UP"}

# Docker healthcheck status
docker inspect --format='{{.State.Health.Status}}' calendar-app  # healthy

# End-to-end via production compose
docker rm -f calendar-app
cp .env.example .env  # then edit POSTGRES_PASSWORD
docker compose -f compose.prod.yaml up -d --build
sleep 30
docker compose -f compose.prod.yaml ps             # both services (healthy)
curl -sS http://localhost:8080/api/actuator/health # {"status":"UP"}
# Postgres should NOT be reachable from the host:
nc -z localhost 5432 && echo "FAIL: Postgres exposed" || echo "OK: Postgres internal only"
docker compose -f compose.prod.yaml down -v
```

---

## Critical files to be created or modified

**Created:**
- `Dockerfile` (repo root)
- `.dockerignore` (repo root)
- `compose.prod.yaml` (repo root)
- `.env.example` (repo root)
- `backend/src/main/java/com/hexlet/calendar/web/SpaFallbackController.java`
- `backend/src/test/java/com/hexlet/calendar/web/SpaFallbackControllerIT.java`

**Modified:**
- `backend/README.md` — append "Production image" section; bump `STEP{1..5}.md` → `STEP{1..6}.md` in the layout list.
- `.gitignore` (repo root) — add `.env`.

**NOT touched:**
- `backend/src/main/resources/application.yml` — env-var override is automatic via Spring Boot relaxed binding; no config change needed.
- `backend/compose.yaml` — stays the dev-mode Postgres-only compose for `./gradlew bootRun`.
- `backend/src/main/resources/static/` — written into only inside the Docker stage; Git tree stays clean.
- `frontend/` — production build is invoked from the Dockerfile via `npm run build`; no source changes.
- All other tests.

---

## Commit plan

Two commits, separating the in-tree source change from the deployment-wrapper additions so the source change can be reviewed and reverted independently if it ever needs to be:

1. `feat(backend): SpaFallbackController for client-side routing` — adds `SpaFallbackController.java` + `SpaFallbackControllerIT.java`. Touches only `backend/`. Independently shippable: `./gradlew clean build` stays green; runtime behaviour is unchanged until a `static/` payload is present.
2. `feat: docker image bundling backend + frontend (phase 6)` — `Dockerfile`, `.dockerignore`, `compose.prod.yaml`, `.env.example`, root `.gitignore` update, README "Production image" section.

---

## Hand-off — what's still left after Phase 6

1. **Frontend vite-proxy PR** (CLAUDE.md §9) — still independently useful for dev-mode integration with the real backend (`npm run dev` against `./gradlew bootRun`). Production image doesn't need it.
2. **CI image publishing** — pushing `calendar-app:<sha>` to a registry (GHCR, Docker Hub, ECR) on main-branch builds. Not in this phase.
3. **Production secrets management** — the env-var pattern works, but a real deploy needs a secrets store (Kubernetes secrets, AWS SSM, etc.). Out of scope.
4. **Read-only root filesystem** — `--read-only` flag plus `--tmpfs /tmp` for further hardening. Optional.
5. **JVM container tuning** — `-XX:MaxRAMPercentage=75` if memory limits are tight. JDK 21 defaults are usually fine.

---

## Post-run notes (what actually shipped)

`./gradlew clean build` is `BUILD SUCCESSFUL`. 49 tests green: 15 `SlotMathTest`, 3 `AvailabilityServiceTest`, 5 `AvailableSlotsControllerIT`, 9 `ScheduledEventControllerIT`, 1 `ConcurrencyIT`, 5 `ErrorMappingIT`, 6 `EventTypeControllerIT`, 5 `SpaFallbackControllerIT` (one more than the planned 4 — see Deviation 4). Plan said "44 → 48"; reality is 44 → 49.

`docker compose -f compose.prod.yaml up -d --build` brings up both services `(healthy)`; the manual curl matrix below all returned the expected responses against a fresh stack, end-to-end:

- `/`, `/admin/event-types`, `/admin/bookings`, `/book/et_intro-call_abc123` → `200 text/html` (index.html or SPA-fallback forward).
- `/favicon.svg` → `200 image/svg+xml`; `/assets/index-Btz9-k5d.js` → `200 text/javascript`.
- `/api/calendar/event_types` → `[]`; `POST` then `GET` round-trip and `available_slots?clientTimeZone=Europe/Berlin` shape match the spec.
- `/api/actuator/health` → `{"status":"UP"}`; Docker `HEALTHCHECK` reports `healthy`.
- `/typo` and `/api/this-doesnt-exist` → typed `{"code":404,"message":"Not found: …"}`. (Was 500 in the first verification pass — see Deviation 5.)
- `nc -z localhost 5432` fails: Postgres internal-only.

Created/modified files match the plan, with the additions documented below.

### Deviation 1 — `ScheduledEventService.create()` got the alignment-race + deadlock fix

Plan said "Not changed by Phase 6: all `src/main/java/**` source except a single new `SpaFallbackController`". `ConcurrencyIT` had been claimed green at end of STEP5; it's actually flaky on this system (3 of 4 isolated runs failed under the original plan's assumptions). Debug logging surfaced three legitimate race outcomes, only one of which the production code mapped to a sensible HTTP status:

1. **Postgres deadlock during EXCLUDE check (SQLSTATE `40P01`)** — both threads insert simultaneously, the GiST exclusion check requires per-tuple ShareLocks, and the lock cycle aborts one transaction with `CannotAcquireLockException`. The DB still settles to one row. The original code let this propagate to the catch-all `Exception` handler → 500.
2. **Clean exclusion violation (`23P01`)** — already handled correctly → 409.
3. **Alignment-race** — thread A commits between thread B's `existsOverlapping` (line 104, only sees committed data under READ_COMMITTED) and `listAvailableSlots` (recomputes a fresh DB read). B's alignment check then rejects the (now-booked) slot as `BadRequestException` → 400. Conflates a race outcome with a static caller-error.

Fix (~6 lines):

- On alignment failure, re-check `existsOverlapping` (which now sees the other thread's commit). If true → `ConflictException` (409); otherwise → `BadRequestException` (400). Disambiguates race vs. caller-error.
- Catch `CannotAcquireLockException` around `saveAndFlush` → `ConflictException`. Postgres deadlock during EXCLUDE check is semantically a slot collision for the loser, not a 5xx.

Net effect: every race outcome surfaces as 409 with the same `Error` body. `ConcurrencyIT` passes deterministically on subsequent runs (3 consecutive `BUILD SUCCESSFUL` in row to verify).

### Deviation 2 — Option B URL refactor (dropped `server.servlet.context-path: /api`)

The plan assumed `server.servlet.context-path: /api` (CLAUDE.md §7) was compatible with serving the React app at `/`. It isn't — Spring serves the *entire* application (controllers, static resources, actuator) under the context-path. Verification curls confirmed: with the original config, `http://localhost:8080/` → 404; only `http://localhost:8080/api/` returned index.html, and even then the Vite-built `index.html` references `/favicon.svg` and `/assets/...` (no `/api` prefix) so the browser would 404 on every asset.

Two paths considered: (A) embrace the `/api/` URL prefix for the whole app and add `base: "/api/"` to `vite.config.ts`; (B) drop the context-path, prefix controllers explicitly, and serve static at `/`. Took (B) — matches CLAUDE.md §9's stated design ("React serves from `/`, API from `/api/*`, no reverse proxy needed in production").

Concrete changes (none of which the original STEP6 plan called out as in scope):

- `application.yml`: removed `server.servlet.context-path: /api`; added `management.endpoints.web.base-path: /api/actuator` so the actuator URL stays at `/api/actuator/health`.
- `CalendarController`: added class-level `@RequestMapping("/api")`. The generated `CalendarApi` interface puts `@RequestMapping(value = "/calendar/...")` on each method, and Spring concatenates → `/api/calendar/...`. No change to the OpenAPI spec or generator config.
- All 4 web ITs (`AvailableSlotsControllerIT`, `ErrorMappingIT`, `EventTypeControllerIT`, `ScheduledEventControllerIT`): 29 `mockMvc.perform(...("/calendar/...")` → `("/api/calendar/...")` rewrites. STEP6 had explicitly listed these as "NOT touched"; they had to change because the URL space genuinely moved.

`ConcurrencyIT` was untouched (no MockMvc paths — it calls `ScheduledEventService` directly).

### Deviation 3 — `SpaFallbackController` regex → explicit route prefixes

The plan's regex `{"/{path:[^.]*}", "/**/{path:[^.]*}"}` (already a correction of the original draft's first-segment-anchored bug) caught any non-dot path including `/api/whatever-unmapped` once context-path was removed. With Option B in place, an unmapped API path would be silently forwarded to `index.html` and broken API clients would get HTML instead of a 404 JSON body.

Replacement: enumerate the actual React Router prefixes from `frontend/src/routes/index.tsx`:

```java
@GetMapping(value = {"/admin/**", "/book/**"})
String forward() { return "forward:/index.html"; }
```

`/` is served by Spring's static resource handler for free; `/admin/...` and `/book/...` cover every non-root SPA route the frontend declares. New top-level routes will require updating this list — flagged as a deliberate boundary contract rather than a regex tuned to "anything that doesn't look like an asset". A single test (`unmappedApiPath_returns404_notForwarded`) anchors that `/api/foo` is not swallowed.

### Deviation 4 — `SpaFallbackControllerIT` has 5 tests, not 4

Plan listed 4: single-segment forward, two-segment forward, dotted-asset non-forward, API non-forward. After Deviation 3 the regex contract collapsed; the test file was rewritten around the prefix contract:

1. `adminRoute_forwardsToIndexHtml` — `/admin/event-types` and `/admin/bookings`.
2. `bookRoute_forwardsToIndexHtml` — `/book/et_intro-call_abc123`.
3. `apiPath_isNotForwarded_handledByCalendarController` — `/api/calendar/event_types` returns 200 from the controller.
4. `unknownTopLevelPath_returns404_notForwarded` — `/typo` returns a typed 404, not a forward and not 500.
5. `unmappedApiPath_returns404_notForwarded` — `/api/this-endpoint-does-not-exist` returns a typed 404.

The dotted-asset test from the original plan was dropped: with the prefix-based controller, `/assets/foo.js` doesn't match `/admin/**` or `/book/**` regardless of the dot, so the regex-anchoring rationale no longer applies.

### Deviation 5 — `ApiExceptionHandler` got a `NoResourceFoundException` → 404 mapping

Surfaced in the verification curls: `/typo` and `/api/this-doesnt-exist` returned 500. Spring's `DispatcherServlet` raises `NoResourceFoundException` when no controller or static resource matches; the existing `@ExceptionHandler(Exception.class)` catch-all swallowed it as a 500 with "Internal server error". Real bug, not specific to STEP6 but only visible once an unmapped path could actually reach the dispatcher (under context-path `/api` everything outside it 404'd from Tomcat directly).

Fix: a 4-line `@ExceptionHandler(NoResourceFoundException.class)` that returns `{"code":404,"message":"Not found: <path>"}`. Tests in Deviation 4 anchor it.

### Deviation 6 — image size 264 MB, not the planned ≤120 MB

The plan's exit criterion 1 said `≤120 MB`. Reality on this system: 264 MB. Breakdown:

- `eclipse-temurin:21-jre-alpine` base ≈ 210 MB (JRE 21 with all standard modules; the alpine JRE is bigger than older estimates because Temurin ships full JRE, not a stripped one).
- Spring Boot fat JAR with our deps (Hibernate, Postgres driver, Flyway, Tomcat, Validation, Actuator, MapStruct runtime, OpenAPI Jackson nullable, Snake YAML, Logback, Spring Boot Docker Compose support) ≈ 50 MB.
- Frontend `dist/` baked into `static/` ≈ 1 MB.

Hitting ≤120 MB would require either (a) `jlink` to produce a custom JRE with only the modules we use, or (b) Spring Boot's layered JAR + tools.jar trimming. Neither is justified at this scale — the image works, deploys cleanly, and the 120 MB target was an unverified estimate. Exit criterion 1 is therefore **violated as written**; trade-off recorded here.

### Deviation 7 — `node:20-alpine` → `node:24-alpine` in the Dockerfile

User-applied edit during the build cycle. Rebuilds verified to still produce a working image. Original plan had pinned `node:20-alpine`; STEP6.md not retroactively updated (the Dockerfile is the source of truth).

### Deviation 8 — `application.yml` modified

Plan listed `application.yml` under "NOT touched" with the rationale that env-var overrides via Spring relaxed binding don't require config changes for `SPRING_DATASOURCE_*`. That part still holds. But Deviation 2 forced two structural changes: removing `server.servlet.context-path: /api` and adding `management.endpoints.web.base-path: /api/actuator`. So the file is touched, just not for the env-var reason the plan was reasoning about.

### Hand-off updates for downstream work

- The `/api` prefix on API endpoints is now enforced by `@RequestMapping("/api")` on `CalendarController`, not by `server.servlet.context-path`. Anyone adding a new controller class outside `CalendarController` must remember to also annotate it (or an upstream `@RequestMapping("/api")` config). MockMvc test paths must include `/api/...` accordingly.
- The frontend route table in `frontend/src/routes/index.tsx` is now load-bearing for the SPA fallback. Adding a new top-level route (e.g. `/settings`, `/help`) requires adding the corresponding `/<prefix>/**` to `SpaFallbackController.@GetMapping`. A pre-prod smoke test of the full route list is the cheapest way to catch a miss.
- `CannotAcquireLockException` and `NoResourceFoundException` mappings in `ApiExceptionHandler` are now load-bearing for race outcomes and 404 semantics; do not generalize them away in a future refactor without test coverage.
- The duration-minimum manual check from STEP5 (`EventTypeService.create`) is unchanged — STEP6 didn't reopen `spec/openapi.yaml` so the redundancy persists. Still a candidate for cleanup if a future phase adds `minimum: 1` to `CreateEventType.durationMinutes` in the spec.
- The frontend's `vite.config.ts` proxy (`target: "http://127.0.0.1:4010"` + `/api` rewrite) still points at Prism. Switching it to the real backend (`target: "http://127.0.0.1:8080"`, drop the rewrite) is the deferred PR from CLAUDE.md §9 — independently useful for dev-mode but not required by Phase 6 since production serves the React build from Spring's static resources.
