# STEP9 — Single-container deployment (Spring Boot + bundled Postgres)

## Context

After STEP8 the app deploys to Render.com as **two** services: a Docker web service (Spring Boot) and a managed Postgres. That works for Render but is awkward for any platform that hands you a single container slot — fly.io Machines on the cheap tier, a bare VM, a kiosk demo on a colleague's laptop, an "evaluate by `docker run`" handoff. STEP9 adds a third deployment mode: **one Docker image that brings its own Postgres and boots end-to-end with only `PORT` set in the environment.**

The goal:

```
docker run -p 8080:8080 -e PORT=8080 calendar:standalone
```

That's it — no `SPRING_DATASOURCE_*`, no compose file, no sidecar, no managed DB.

This must not regress the two existing modes:
- Local dev (`./gradlew bootRun` with Spring Boot Docker Compose support, [application.yml:23-26](../src/main/resources/application.yml))
- The Render / [compose.prod.yaml](../../compose.prod.yaml) flow that previously used a bare `Dockerfile` plus an external Postgres (STEP6, STEP8)

The artifacts here are mostly **additive** — a new Dockerfile and a new entrypoint script. We also rename the existing `Dockerfile` → `Dockerfile.render` so the new bundled-Postgres image can claim the bare `Dockerfile` slot (see §"Naming convention" below). [render.yaml](../../render.yaml), [compose.prod.yaml](../../compose.prod.yaml), and [backend/README.md](../README.md) get one-line edits to follow the rename.

## Naming convention (which Dockerfile is which)

The deployment platform that hosts the standalone variant auto-detects `./Dockerfile` — there's no way to point it at a custom path. So the bundled-Postgres image **must** be named `Dockerfile`, and the previous render-flavored one moves aside.

After STEP9 the repo has two siblings at the root:

- **`Dockerfile`** — Spring Boot + bundled Postgres in one container. Used by the new single-container mode. Auto-detected by platforms that don't accept a custom Dockerfile path.
- **`Dockerfile.render`** — Spring Boot only, expects an external Postgres via `SPRING_DATASOURCE_*` env vars. Used by [compose.prod.yaml](../../compose.prod.yaml) and the Render web service.

Live references that have to follow the rename (one-line edits each):

| File | Line | Change |
|---|---|---|
| [compose.prod.yaml](../../compose.prod.yaml) | 25 | `dockerfile: Dockerfile` → `dockerfile: Dockerfile.render` |
| [render.yaml](../../render.yaml) | 12 | `dockerfilePath: ./Dockerfile` → `dockerfilePath: ./Dockerfile.render` |
| [backend/README.md](../README.md) | ~39 | Update the "repo-root Dockerfile" prose to name both siblings; show `docker build -f Dockerfile.render` for the render flow. |

**Do not** retroactively edit STEP6.md or STEP8.md. Those documents describe the state at the time they were written; rewriting them would erase the temporal narrative. The current docs (this file, README) are the live source of truth.

**One out-of-repo follow-up:** the manually-created Render service `srv-d7rqr9gg4nts73cu762g` ([STEP8.md §"Deployed service"](STEP8.md)) has its Dockerfile path configured in the dashboard. After the rename, that service's next deploy will fail until the path is updated to `./Dockerfile.render` — either in the dashboard (Settings → Build & Deploy → Dockerfile Path) or via `mcp__render__update_web_service` if that field becomes settable. Verify before merging.

## Why "embedded" approaches don't work

Three temptations rejected up front:

| Option | Verdict | Why |
|---|---|---|
| H2 / HSQLDB / Derby in Postgres-compat mode | No | [V1__init.sql](../src/main/resources/db/migration/V1__init.sql) uses `btree_gist`, `EXCLUDE USING GIST … WITH &&`, `tstzrange`, `JSONB`, `SMALLINT[]`. None of those dialects support that surface. Flyway fails on the first migration. |
| `io.zonky.test:embedded-postgres` as a runtime dep | No | It's a real Postgres (binary extraction), so the schema features work — but the library is explicitly test-scoped. Production use gets you no support, weird first-boot binary-extraction overhead, and a footgun (it owns the DB lifecycle but Spring doesn't know). |
| Spring Boot's Docker Compose support | No | Needs a Docker daemon. Irrelevant once we're already inside a container. |

## Decisions (resolved)

| Question | Decision | Why |
|---|---|---|
| Postgres flavor | Real PostgreSQL 16, installed via Alpine `apk` | Same engine the migrations and tests are written against — no dialect drift. |
| Process model | Two processes (Postgres + JVM) under one shell entrypoint | Spring Boot can't actually "manage" a Postgres lifecycle without the test-only Zonky route. The shell entrypoint is the canonical pattern. |
| Init system | POSIX `sh` entrypoint, no s6/supervisord | One trap, ~30 lines, no extra layer. s6 is overkill for a 1+1 process tree. |
| User | Single non-root user `app` owns both processes | Postgres refuses to run as root anyway. One user keeps PGDATA permissions trivial and avoids `gosu`/`su-exec` on every invocation. |
| Persistence | `VOLUME /var/lib/postgresql/data` declared in the image | With no `-v`, Docker auto-creates an anonymous volume — data persists across `docker stop`/`start` but not `docker rm`. Operators who want durability mount a named volume. |
| App config | **No application code change** | [application.yml:7-9](../src/main/resources/application.yml) already defaults to `jdbc:postgresql://localhost:5432/calendar`, user/pass `calendar`/`calendar`. The bundled Postgres provides exactly that, so there is nothing to wire. |

## Files

### `Dockerfile` (new, repo root — bundled-Postgres variant)

Three stages, same frontend + backend builders as `Dockerfile.render` (copy verbatim — same JAR). Only the runtime stage changes:

- **Base:** `eclipse-temurin:21-jre-alpine` (matches `Dockerfile.render`'s runtime base, keeps the JRE layer cached).
- **Postgres install:** `apk add --no-cache postgresql16 postgresql16-contrib su-exec`.
  - `postgresql16` provides `initdb`, `postgres`, `pg_isready`, `pg_ctl`, `createdb`.
  - `postgresql16-contrib` provides the `btree_gist` extension that [V1__init.sql:1](../src/main/resources/db/migration/V1__init.sql) requires.
  - `su-exec` is reserved for the privilege drop in case we ever need root setup steps; primary path uses `USER app` directly.
- **User + PGDATA:** create `app:app`, `mkdir -p /var/lib/postgresql/data && chown app:app …` and `chmod 700` (Postgres refuses to start if PGDATA permissions are wider than 700).
- **Env baked into the image:**
  - `PGDATA=/var/lib/postgresql/data`
  - `PATH=/usr/libexec/postgresql16:$PATH`
  - `SPRING_DOCKER_COMPOSE_ENABLED=false` — suppresses Spring Boot's Compose auto-configuration ([application.yml:23-26](../src/main/resources/application.yml)). There's no Docker daemon inside the container.
  - `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport` — carried over from [compose.prod.yaml](../../compose.prod.yaml) and STEP8.
- **Volume:** `VOLUME /var/lib/postgresql/data`.
- **Copy:** the JAR to `/app/app.jar` (same path as `Dockerfile.render`) and the entrypoint to `/usr/local/bin/standalone-entrypoint.sh`.
- **Networking:** `EXPOSE 8080`. Healthcheck reuses the shell-form pattern from `Dockerfile.render` (the renamed original): `wget -qO- http://localhost:${PORT:-8080}/api/actuator/health`.
- **Run:** `USER app`, `ENTRYPOINT ["/usr/local/bin/standalone-entrypoint.sh"]`.

### `docker/standalone-entrypoint.sh` (new, executable)

POSIX `sh` (Alpine has no bash by default). Five responsibilities, in order:

1. **First-run init.** If `$PGDATA/PG_VERSION` is absent:
   - `initdb -D "$PGDATA" --username=calendar --auth-local=trust --auth-host=trust --encoding=UTF8`.
   - Overwrite `pg_hba.conf` with only `host all all 127.0.0.1/32 trust` and `local all all trust`. Restricting auth to loopback is non-negotiable: the JVM and Postgres share the network namespace, and we don't want any external 5432 access.
   - Overwrite `postgresql.conf` to `listen_addresses='127.0.0.1'`, `unix_socket_directories='/tmp'`.
   - Briefly `pg_ctl start`, `createdb -U calendar calendar`, `pg_ctl stop -m fast`.
   - Idempotent: skipped on subsequent boots because `$PGDATA` is volume-backed.
2. **Background Postgres.** `postgres -D "$PGDATA" &`; capture `$!` as `PG_PID`.
3. **Signal hygiene.** `trap 'pg_ctl stop -D "$PGDATA" -m fast; wait $PG_PID' INT TERM EXIT`. Without this, killing the container leaves Postgres mid-write; with `-m fast`, it gets a clean shutdown.
4. **Wait for ready.** `until pg_isready -h 127.0.0.1 -U calendar -d calendar -q; do sleep 0.5; done`. HikariCP would retry anyway, but waiting here surfaces Postgres failures *before* Spring's bootstrap and produces clean log ordering.
5. **Hand to JVM.** `java -jar /app/app.jar &` then `wait` on the JVM PID. **Do not `exec`** — `exec` replaces the shell, the trap never fires, and Postgres is orphaned at shutdown.

The script never reads `$PORT`. Spring Boot already honors `${PORT:8080}` ([application.yml:2](../src/main/resources/application.yml)); the shell stays out of it. The Dockerfile's healthcheck reads `$PORT` itself (shell-form, expanded at runtime).

## Files NOT changed

- [backend/src/main/resources/application.yml](../src/main/resources/application.yml) — defaults already point at localhost with the right db name and credentials.
- The contents of `Dockerfile.render` (the renamed original) — only its filename moves; its build instructions stay byte-identical.
- [backend/build.gradle.kts](../build.gradle.kts) — no new runtime deps. Same JAR.
- Frontend, all Java code, all migrations.
- STEP6.md / STEP7.md / STEP8.md — historical chapters, left intact even though they reference the old `Dockerfile` name.

## Build & run

```bash
# Auto-detected at ./Dockerfile — no -f flag needed
docker build -t calendar:standalone .

# Ephemeral (anonymous volume — data lost on `docker rm`)
docker run --rm -p 8080:8080 -e PORT=8080 calendar:standalone

# Persistent across `docker rm`
docker run -p 8080:8080 -e PORT=8080 \
  -v calendar-data:/var/lib/postgresql/data \
  calendar:standalone
```

First boot logs, in order: `initdb` output → "database system is ready to accept connections" → Spring Boot banner → Flyway applies V1+V2 → `Started CalendarApplication` → Tomcat on port 8080. Subsequent boots skip the `initdb` branch and start in the time it takes Postgres to recover its WAL plus the JVM cold start.

## Verification

1. **Build:** `docker build -t calendar:standalone .` — final image inspects to ~250 MB (eclipse-temurin alpine ~95 MB + postgres16 + contrib ~80 MB + JAR + frontend `dist/`).
2. **First-boot run:** `docker run --rm -p 8080:8080 -e PORT=8080 calendar:standalone`. Confirm log ordering above.
3. **Health:** `curl http://localhost:8080/api/actuator/health` → `{"status":"UP"}`.
4. **Functional smoke** (mirrors [STEP8.md §Verification](STEP8.md)):
   - `POST /api/calendar/event_types` creates an event type, returns `et_…` id.
   - `GET /api/calendar/event_types/<id>/available_slots?clientTimeZone=Europe/Berlin` returns the 14-day grouping.
   - `POST /api/calendar/scheduled_events` books a slot; second POST to the same slot returns 409.
5. **SPA fallback:** `curl http://localhost:8080/` returns the React SPA shell.
6. **Persistence (volume mount):** Re-run with `-v calendar-data:/var/lib/postgresql/data`, create an event type, `docker stop` + `docker run` again — the previously created event type is still there.
7. **Persistence (no mount):** Run without `-v`, create data, `docker rm` the container, `docker run` again — empty state (expected).
8. **PORT override:** `docker run -p 9000:9000 -e PORT=9000 calendar:standalone`. Healthcheck at `:9000` returns `UP`; Spring binds to 9000 — verifies the existing `${PORT:8080}` wiring still works.
9. **Signal cleanup:** Run interactively, `Ctrl-C`. Logs should show "received fast shutdown request" from Postgres followed by a clean Spring Boot shutdown — no "PANIC" or recovery messages on the next start (proves the trap fired).
10. **Existing modes still work after the rename:**
    - `./gradlew bootRun` (dev) — unaffected, doesn't touch any Dockerfile.
    - `docker compose -f compose.prod.yaml up` — succeeds with the new `dockerfile: Dockerfile.render` line; produces a byte-identical image to before.
    - Render web service deploy — green only after the dashboard's Dockerfile Path is updated to `./Dockerfile.render`. Confirm by triggering a manual deploy and checking the build log.

## Caveats

- **No backups.** Anonymous volume = data loss on `docker rm` unless mounted. Mounted named volume = no off-host replication, no point-in-time-recovery. For anything beyond demo use, the STEP8 mode (managed Postgres) is the right answer.
- **No multi-instance scale-out.** Two containers means two independent Postgres instances with diverging data — fine for blue/green if you accept data divergence, otherwise use STEP8.
- **Memory.** Postgres + JVM share the container's memory budget. `-XX:MaxRAMPercentage=75` already leaves 25 % for Postgres; tune downward if OOM-kills appear under load.
- **First boot is slower** by ~2 s for `initdb` + db creation. Subsequent boots skip that branch.
- **`btree_gist` extension** must be installed system-wide via `postgresql16-contrib`; the existing `CREATE EXTENSION IF NOT EXISTS` in [V1__init.sql:1](../src/main/resources/db/migration/V1__init.sql) handles activation per database.
- **Loopback-only Postgres.** External clients cannot reach the bundled DB on 5432 — by design. If you need to introspect data from outside, `docker exec -it <container> psql -U calendar calendar`.
