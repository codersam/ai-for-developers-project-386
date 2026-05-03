# Calendar E2E tests

End-to-end tests that exercise the **production Docker image** of the calendar app. The suite brings up `compose.prod.yaml` (Postgres + Spring Boot with the React SPA baked in), runs the golden-path booking flow plus an SPA-routing smoke test in Chromium, then tears the stack down.

## Prerequisites

1. **Docker Desktop running** — the suite drives `compose.prod.yaml`.
2. **Node ≥ 20** on `PATH`.
3. **`.env` at the repo root** with at least `POSTGRES_PASSWORD` set. Copy `.env.example`:
   ```sh
   cp ../.env.example ../.env
   # then edit ../.env and set a real POSTGRES_PASSWORD
   ```
   Without this, `docker compose -f compose.prod.yaml up` aborts.
4. **Port 8080 free** on the host.
5. **Disk + time budget** — the first run does a multi-stage build (Node + Gradle); expect ~3–5 min cold, seconds warm.

## Install

```sh
cd e2e
npm install
npx playwright install --with-deps chromium
```

`--with-deps` pulls native Linux libraries when running in CI; on macOS it's a no-op.

## Run

```sh
npm run test:e2e
```

Behind the scenes Playwright will:

1. `docker compose -f compose.prod.yaml up -d --build --wait` — blocks on the Postgres and `/api/actuator/health` healthchecks.
2. Run the two specs against `http://localhost:8080`.
3. `docker compose -f compose.prod.yaml down -v` — wipes the Postgres volume so the next run starts clean.

### Faster iteration: reuse a running stack

```sh
# in one terminal
docker compose -f compose.prod.yaml up -d --build --wait

# in another, every time:
E2E_REUSE_STACK=1 npm run test:e2e
```

`E2E_REUSE_STACK=1` skips both `globalSetup` (the bring-up) and `globalTeardown` (the `down -v`), so reruns take seconds. Database state from the previous run is **kept** — useful for debugging, but tests assume an empty schema, so wipe with `docker compose -f compose.prod.yaml down -v` between runs that depend on a clean slate.

### Headed mode and the UI runner

```sh
npm run test:e2e:headed   # watch the browser drive the UI
npm run test:e2e:ui       # Playwright UI runner with time-travel debugging
```

## Reports and traces

After any run:

```sh
npm run report
```

…opens the HTML report from `playwright-report/`. On failure, `test-results/<spec>/` contains:

- `test-failed-*.png` — screenshot at the failure point
- `video.webm` — full video of the failed test
- `trace.zip` — Playwright trace; open with `npx playwright show-trace test-results/.../trace.zip`

## What this suite covers

- **Golden path** (`tests/main-flow.spec.ts`): owner creates an event type → guest picks a slot and books → owner sees the booking on `/admin/bookings`. Validates that the React UI, Spring controllers, Flyway-migrated schema and Postgres `EXCLUDE USING GIST` constraint work together.
- **SPA fallback** (`tests/spa-routing.spec.ts`): every primary deep link loads with a 200, and a hard refresh on an admin route doesn't 404 — locks in the contract of `SpaFallbackController`.

## What this suite does *not* cover

- Cross-browser (Firefox, WebKit) — Chromium only.
- Concurrency / collision (already covered by `ConcurrencyIT` at the JVM level).
- Visual regression, mobile viewports, API-direct contract tests, authentication.
