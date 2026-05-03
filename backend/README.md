# Calendar Backend

Spring Boot 3.4 service that fulfils the OpenAPI contract in `spec/openapi.yaml`,
backed by PostgreSQL 16 via Flyway migrations.

## Prerequisites
- Docker (for the local Postgres container)
- JDK 21

## Run
    ./gradlew bootRun

Spring Boot's Docker Compose support (`compose.yaml`) starts Postgres on `:5432`,
waits for the healthcheck, then boots the app on `:8080`. Flyway applies
`V1__init.sql` and `V2__seed_owner_calendar.sql` on first start.

## Test
    ./gradlew clean build

Testcontainers spins up `postgres:16-alpine` for the integration phase. No local
Postgres needed for tests.

## Endpoints
Controllers serve OpenAPI paths under `server.servlet.context-path: /api`.

- `POST /api/calendar/event_types`
- `GET  /api/calendar/event_types`
- `POST /api/calendar/scheduled_events`
- `GET  /api/calendar/scheduled_events`
- `GET  /api/calendar/scheduled_events/{id}`
- `GET  /api/calendar/event_types/{id}/available_slots?clientTimeZone=...`

## Health
    curl http://localhost:8080/api/actuator/health

Only the `health` actuator endpoint is exposed; details are hidden.

## Production image
The repo ships two Dockerfiles at the root, one per deployment mode:

- `Dockerfile.render` — Spring Boot only; expects an external Postgres reachable
  via `SPRING_DATASOURCE_*` env vars. Used by [compose.prod.yaml](../compose.prod.yaml)
  and the Render web service ([render.yaml](../render.yaml)).
- `Dockerfile` — Spring Boot + bundled Postgres in a single container; boots
  with only `PORT` set. See [docs/STEP9.md](docs/STEP9.md). The plain name is
  required because some platforms auto-detect `./Dockerfile`.

Build the render-style image and run it against an external Postgres:

    docker build -f Dockerfile.render -t calendar-app .
    docker run --rm -p 8080:8080 \
      -e SPRING_DATASOURCE_URL=jdbc:postgresql://<db-host>:5432/calendar \
      -e SPRING_DATASOURCE_USERNAME=calendar \
      -e SPRING_DATASOURCE_PASSWORD=calendar \
      calendar-app

Or use the production compose file at the repo root (Postgres + app, no
host-exposed DB port):

    cp .env.example .env
    # edit .env and set POSTGRES_PASSWORD
    docker compose -f compose.prod.yaml up -d --build
    docker compose -f compose.prod.yaml ps   # both services should report (healthy)

The frontend serves from `/`, the API from `/api/*`, and the health endpoint
from `/api/actuator/health`.

## Layout
- `spec/openapi.yaml` — contract source; generated DTOs + interfaces land in `build/generated/openapi/`.
- `src/main/resources/db/migration/` — Flyway migrations.
- `src/main/java/com/hexlet/calendar/` — application code.
- `docs/STEP{1..6}.md` — phase-by-phase implementation history.
