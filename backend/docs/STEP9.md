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
- **Postgres install:** `apk add --no-cache postgresql16 postgresql16-contrib` — Alpine 3.23 ships PostgreSQL 16.13. `postgresql16` provides `initdb`, `postgres`, `pg_isready`, `pg_ctl`, `createdb`, all in `/usr/bin` (already on `$PATH`, so no `PATH` override is needed). `postgresql16-contrib` provides the `btree_gist` extension that [V1__init.sql:1](../src/main/resources/db/migration/V1__init.sql) requires. (The earlier draft of this plan also installed `su-exec`; dropped during implementation because the entrypoint never has to drop privileges — both processes run as the single `app` user from `USER app`.)
- **User + PGDATA:** create `app:app`, `mkdir -p /var/lib/postgresql/data && chown app:app …` and `chmod 700` (Postgres refuses to start if PGDATA permissions are wider than 700).
- **Env baked into the image:**
  - `PGDATA=/var/lib/postgresql/data`
  - `SPRING_DOCKER_COMPOSE_ENABLED=false` — suppresses Spring Boot's Compose auto-configuration ([application.yml:23-26](../src/main/resources/application.yml)). There's no Docker daemon inside the container.
  - `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport` — carried over from [compose.prod.yaml](../../compose.prod.yaml) and STEP8.
- **Volume:** `VOLUME /var/lib/postgresql/data`.
- **Copy:** the JAR to `/app/app.jar` (same path as `Dockerfile.render`) and the entrypoint to `/usr/local/bin/standalone-entrypoint.sh`.
- **Networking:** `EXPOSE 8080`. Healthcheck reuses the shell-form pattern from `Dockerfile.render` (the renamed original) but bumps `--start-period` from `30s` to `60s` because the container has to boot Postgres *and* the JVM before the actuator endpoint answers: `wget -qO- http://localhost:${PORT:-8080}/api/actuator/health`.
- **Run:** `USER app`, `ENTRYPOINT ["/usr/local/bin/standalone-entrypoint.sh"]`.

### `docker/standalone-entrypoint.sh` (new, executable)

POSIX `sh` (Alpine has no bash by default). Five responsibilities, in order:

1. **First-run init.** If `$PGDATA/PG_VERSION` is absent:
   - `initdb -D "$PGDATA" --username=calendar --auth-local=trust --auth-host=trust --encoding=UTF8 --locale=C`. The `--locale=C` was added during implementation because Alpine ships only the C locale by default — without it, `initdb` warns and picks an arbitrary fallback.
   - Overwrite `pg_hba.conf` with three lines: `local all all trust`, `host all all 127.0.0.1/32 trust`, and `host all all ::1/128 trust` (the IPv6 loopback line was added belt-and-suspenders for runtimes where the container has IPv6 enabled). Restricting auth to loopback is non-negotiable: the JVM and Postgres share the network namespace, and we don't want any external 5432 access.
   - Overwrite `postgresql.conf` to `listen_addresses='127.0.0.1'`, `unix_socket_directories='/tmp'`.
   - Briefly `pg_ctl -o "-k /tmp" -w start`, `createdb -h /tmp -U calendar calendar`, `pg_ctl -m fast -w stop`. The `-k /tmp` is required because the daemon's default socket directory (`/run/postgresql`) doesn't exist in this image and isn't writable by the `app` user.
   - Idempotent: skipped on subsequent boots because `$PGDATA` is volume-backed.
2. **Background Postgres.** `postgres -D "$PGDATA" -k /tmp &`; capture `$!` as `PG_PID`.
3. **Signal hygiene.** A `shutdown()` function bound via `trap shutdown TERM INT EXIT`. The function first sends `SIGTERM` to the JVM and `wait`s on it, then `pg_ctl -m fast`s Postgres. The plan's earlier draft suggested an inline trap that stopped Postgres directly; the function form was chosen during implementation so the JVM gets a chance to finish flushing before Postgres goes away, and so the same logic handles both signal-driven and natural-exit shutdowns.
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

1. **Build:** `docker build -t calendar:standalone .` — measured final image: **298 MB** on first build (eclipse-temurin alpine + postgres16 + contrib + JAR + frontend `dist/`). A bit larger than the ~250 MB in the original estimate; the `postgresql16-contrib` package alone is ~80 MB and pulls in Perl/Python deps via its JIT extras even when the base `postgresql16-contrib-jit` package is not installed.
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

## Implementation notes (post-build)

Captured from the actual build + smoke run, so a future reader can compare against expected behavior:

- **Image size:** 298 MB. Larger than the planned ~250 MB; not a concern for the target use cases but worth flagging if size becomes a constraint (could be trimmed by switching to the smaller `postgres:16-alpine` image as the base and layering JDK on top, at the cost of losing the cached JRE layer shared with `Dockerfile.render`).
- **Postgres version:** 16.13 (whatever Alpine 3.23.4 ships). Major version match with `postgres:16-alpine` used in [compose.prod.yaml](../../compose.prod.yaml) and STEP8 — minor versions may differ.
- **Cold start:** `Started CalendarApplication in 9.113 seconds` on first boot, with Postgres `initdb` adding ~1 s before the JVM starts. Subsequent boots skip `initdb`. The 60-second `start-period` in `HEALTHCHECK` is comfortably above this.
- **Clean-shutdown signature.** A `docker stop` produces this Postgres log sequence; if you ever see `database system was interrupted` on the next start, the trap didn't fire:
  ```
  LOG:  shutting down
  LOG:  checkpoint starting: shutdown immediate
  LOG:  checkpoint complete: …
  LOG:  database system is shut down
  waiting for server to shut down.... done
  server stopped
  ```
- **Persistence verified.** `docker run -v calendar-data:/var/lib/postgresql/data` → create event type → `docker stop && docker rm` → relaunch with same volume → event type still present. No `initdb` log lines on the second boot, as expected.
- **One Hibernate log oddity:** at startup, Hibernate logs `Database driver: undefined/unknown` and several `undefined/unknown` pool fields. This is cosmetic — Hibernate 6 + HikariCP no longer exposes those metadata fields the way Hibernate's connection pooling logger expects. Functional behavior is unaffected.

## Caveats

- **No backups.** Anonymous volume = data loss on `docker rm` unless mounted. Mounted named volume = no off-host replication, no point-in-time-recovery. For anything beyond demo use, the STEP8 mode (managed Postgres) is the right answer.
- **No multi-instance scale-out.** Two containers means two independent Postgres instances with diverging data — fine for blue/green if you accept data divergence, otherwise use STEP8.
- **Memory.** Postgres + JVM share the container's memory budget. `-XX:MaxRAMPercentage=75` already leaves 25 % for Postgres; tune downward if OOM-kills appear under load.
- **First boot is slower** by ~2 s for `initdb` + db creation. Subsequent boots skip that branch.
- **`btree_gist` extension** must be installed system-wide via `postgresql16-contrib`; the existing `CREATE EXTENSION IF NOT EXISTS` in [V1__init.sql:1](../src/main/resources/db/migration/V1__init.sql) handles activation per database.
- **Loopback-only Postgres.** External clients cannot reach the bundled DB on 5432 — by design. If you need to introspect data from outside, `docker exec -it <container> psql -U calendar calendar`.
