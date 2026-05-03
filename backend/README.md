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

## Layout
- `spec/openapi.yaml` — contract source; generated DTOs + interfaces land in `build/generated/openapi/`.
- `src/main/resources/db/migration/` — Flyway migrations.
- `src/main/java/com/hexlet/calendar/` — application code.
- `docs/STEP{1..5}.md` — phase-by-phase implementation history.
