# STEP8 — Deploy to Render.com (Blueprint)

## Context

After STEP6 the repo ships a single deployable Docker image (Spring Boot + React `dist/` baked into `src/main/resources/static/`), and STEP7 added a Playwright E2E suite that runs the production image via [compose.prod.yaml](../../compose.prod.yaml). STEP8 takes that same image and deploys it to a hosted environment.

The user asked specifically: **"Can I deploy as a Compose file so Postgres starts at the same time?"** The short answer is *no*, but Render has a near-equivalent.

## Decisions (resolved)

| Question | Decision | Why |
|---|---|---|
| Provider | Render.com | User chose it. Free tier supports Docker web services + managed Postgres. |
| Multi-service orchestration | **Render Blueprint (`render.yaml`)** | Render does **not** support `docker-compose.yml`. Blueprints are the official Render-native equivalent — declare web service + database in one file, deploy together. |
| Postgres | Render **managed Postgres** (not a container) | On Render you do not run `postgres:16-alpine` as your own container. You declare a `databases:` entry and Render provisions a managed instance. |
| Build | The existing [Dockerfile](../../Dockerfile) (unchanged) | Multi-stage build already produces a slim runtime image. Render's `runtime: docker` builds it directly. |
| Region | `frankfurt` | Owner timezone in [application.yml](../src/main/resources/application.yml) is `Europe/Berlin`; locality reduces latency for the demo. |
| Plan | `free` for both services | Hexlet project — free tier is sufficient. **Caveat:** free Render Postgres expires **30 days** after creation; the web service spins down after 15 minutes of inactivity (cold start ~30 s). |

## Render ≠ Docker Compose: the gaps to bridge

Three differences between [compose.prod.yaml](../../compose.prod.yaml) and what Render expects:

### 1. Port

Render injects a `PORT` env var into the container and forwards external HTTPS to whatever port the app listens on. Two changes make the image honour that:

- [application.yml](../src/main/resources/application.yml): `server.port: ${PORT:8080}` — the app binds to `$PORT` when set, otherwise `8080`.
- [Dockerfile](../../Dockerfile) `HEALTHCHECK`: `wget -qO- http://localhost:${PORT:-8080}/...` — same fallback, shell-form CMD so the env var expands.

The Blueprint pins `PORT=8080` so Render and the local image agree on a single port. If you drop that env var, Render defaults `PORT` to `10000` and both the app and the in-container healthcheck follow. The compose-level healthcheck in [compose.prod.yaml:42](../../compose.prod.yaml) hardcodes `:8080`, which is correct because compose.prod.yaml never sets `PORT`.

### 2. JDBC URL format

Render's managed-Postgres `connectionString` property returns a **libpq-format** URL:

```
postgresql://user:password@host:port/database
```

Spring's JDBC driver requires the `jdbc:` prefix and — critically — **does not accept the `user:password@host` part**. That's libpq-only syntax. Just prepending `jdbc:` to Render's connection string yields `jdbc:postgresql://user:password@host/db`, which the driver rejects with:

```
Driver org.postgresql.Driver claims to not accept jdbcUrl, jdbc:postgresql://...
```

(We hit this on the first MCP-driven deploy — see §"Actual deploy" below.)

The driver accepts only the `host[:port]/database` form, with credentials passed separately. Render Blueprints don't support string interpolation between `fromDatabase` properties either, so we can't compose the JDBC URL declaratively from `host`/`port`/`database`.

**Resolution (Blueprint path):** the Blueprint declares `SPRING_DATASOURCE_URL` with `sync: false` (placeholder — not auto-synced from the DB). After the first Blueprint apply, set it **once** in the Render dashboard for the web service:

```
jdbc:postgresql://<INTERNAL_HOST>/<DB_NAME>
```

Where `<INTERNAL_HOST>` is the **Internal Database URL** host shown on the DB page (looks like `dpg-abc123-a` — no `.frankfurt-postgres.render.com` suffix, no `:5432` needed) and `<DB_NAME>` is the actual `databaseName` from the DB page (Render may have suffixed it — see "Database name surprise" in §"Actual deploy"). Username + password are kept as separate env vars (`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`), wired via `fromDatabase`. Once set, redeploys reuse the values.

### 3. No `depends_on`

Render starts services independently. The web service may boot while the DB is still initializing — Spring Boot will retry the connection at startup, and Flyway runs once the DB is reachable. No code change needed; just expect a longer first deploy.

## Files

### [render.yaml](../../render.yaml) (new, repo root)

```yaml
databases:
  - name: calendar-db
    plan: free
    databaseName: calendar
    user: calendar
    postgresMajorVersion: "16"

services:
  - type: web
    name: calendar-app
    runtime: docker
    dockerfilePath: ./Dockerfile
    plan: free
    region: frankfurt
    healthCheckPath: /api/actuator/health
    envVars:
      - key: PORT
        value: "8080"
      - key: SPRING_DATASOURCE_URL
        sync: false
      - key: SPRING_DATASOURCE_USERNAME
        fromDatabase: { name: calendar-db, property: user }
      - key: SPRING_DATASOURCE_PASSWORD
        fromDatabase: { name: calendar-db, property: password }
      - key: SPRING_DOCKER_COMPOSE_ENABLED
        value: "false"
      - key: JAVA_TOOL_OPTIONS
        value: "-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"
```

`SPRING_DOCKER_COMPOSE_ENABLED=false` disables Spring Boot's Docker Compose support (the dev-only loop in [application.yml:24-26](../src/main/resources/application.yml)) — there is no Docker daemon inside the Render container.

`healthCheckPath: /api/actuator/health` matches the existing actuator exposure ([application.yml:31-39](../src/main/resources/application.yml)). Render uses it both for readiness gating and for zero-downtime deploys.

## Deploy procedure

1. Commit [render.yaml](../../render.yaml).
2. Push the branch to GitHub (Render reads the Blueprint from a connected repo).
3. On Render: **New → Blueprint → pick this repo**. Render parses `render.yaml`, shows the planned services, and provisions both on confirm.
4. **One-time wiring** (because of the JDBC URL gap):
   - Wait for `calendar-db` to reach status *Available*.
   - Open `calendar-db` → copy the **Internal Database URL** host (e.g. `dpg-abc123-a`).
   - Open `calendar-app` → **Environment** → set `SPRING_DATASOURCE_URL` to `jdbc:postgresql://<that-host>:5432/calendar`.
   - Save → triggers a redeploy.
5. First boot runs Flyway (`V1__init.sql`, `V2__seed_owner_calendar.sql`), Hibernate validates the schema, the actuator health endpoint returns `UP`, and Render flips the service to *Live*.

## Actual deploy (via Render MCP) and what we learned

Instead of the Blueprint flow above, we drove the deploy through the Render MCP server (`create_web_service`, `update_environment_variables`, `list_deploys`, `get_deploy`). The Blueprint file [render.yaml](../../render.yaml) still lives at the repo root for reference, but it was **not applied** — the running service was created directly. Lessons captured from that run:

### The MCP `create_web_service` *does* support Docker

The tool's description says it "only supports web services which don't use Docker, or a container registry," but the `runtime` enum includes `docker` and the call **succeeds** with `runtime: "docker"`. Render reads the repo's `Dockerfile` automatically; the required `buildCommand` / `startCommand` parameters can be passed as empty strings. Documentation lag, not a functional limitation.

### The MCP `update_web_service` is a stub

It accepts only `serviceId` — no fields for `healthCheckPath`, plan, region, etc. So `healthCheckPath: /api/actuator/health` cannot be set programmatically. Either:
- Set it once in the dashboard (web service → Settings → Health Check Path), **or**
- Skip it — the service still works; Render just won't gate "Live" on Spring being ready, and won't run zero-downtime deploys properly.

### MCP does not expose DB credentials

`get_postgres` returns metadata (id, host segment, region, db name, user) but **not the password**. Sensible security default — but it means the JDBC password must come from the dashboard (DB page → Connections → Password) and be set on the web service via `update_environment_variables`. There is no all-MCP path for the password.

### Database name surprise

We asked Render for a DB called `calendar` but the actual provisioned `databaseName` was **`calendar_iivc`** (Render uniqueified it, likely due to a prior collision). The JDBC URL must use the real name. Always read `databaseName` back from `get_postgres` rather than assuming what you requested.

### Internal hostname format

For service-to-service connections in the same region, the host is just the database id segment — `dpg-d7rq22pkh4rs73f07afg-a` — with **no suffix and no port**. The full external host (`dpg-….frankfurt-postgres.render.com`) is for connections from outside Render. Mixing them up causes "host not found" or routes through the public internet unnecessarily.

### Final env-var configuration that worked

```
PORT                          = 8080
SPRING_DATASOURCE_URL         = jdbc:postgresql://dpg-d7rq22pkh4rs73f07afg-a/calendar_iivc
SPRING_DATASOURCE_USERNAME    = calendar
SPRING_DATASOURCE_PASSWORD    = <from dashboard>
SPRING_DOCKER_COMPOSE_ENABLED = false
JAVA_TOOL_OPTIONS             = -XX:MaxRAMPercentage=75 -XX:+UseContainerSupport
```

Note: **no embedded credentials in the URL.** The first attempt pasted Render's `postgresql://user:pass@host/db` and prepended `jdbc:` — Hikari rejected it (see §2 above).

### Deployed service

- **Name:** `hexlet-calendar-codersam` (different from `render.yaml`'s `calendar-app` because we created it manually, not via Blueprint)
- **Service ID:** `srv-d7rqr9gg4nts73cu762g`
- **URL:** `https://hexlet-calendar-codersam.onrender.com`
- **Region:** frankfurt (same as DB → uses internal networking)

## Verification

After the deploy reached *Live*:

- `https://hexlet-calendar-codersam.onrender.com/api/actuator/health` → `{"status":"UP"}`
- `https://hexlet-calendar-codersam.onrender.com/` serves the React SPA (the SPA fallback from STEP6 handles deep links).
- `POST https://hexlet-calendar-codersam.onrender.com/api/calendar/event_types` creates an event type; the booking flow works end-to-end against managed Postgres.

## Caveats

- **Free Postgres expires after 30 days** — Render emails a warning; upgrade or recreate before expiry.
- **Cold starts** on the free web plan: after 15 min idle, the next request waits ~30 s for the container to spin up. Acceptable for a demo, not for production.
- **No persistent disk needed** — managed Postgres has its own storage; the `calendar-pgdata-prod` volume in [compose.prod.yaml:53](../../compose.prod.yaml) has no Render counterpart.
- **The Playwright E2E suite from STEP7 still runs locally** against `compose.prod.yaml`. It is not adapted to run against Render and shouldn't be — the suite tears down the DB volume between runs, which would destroy a shared remote DB.
