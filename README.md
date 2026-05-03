### Hexlet tests and linter status:
[![Actions Status](https://github.com/codersam/ai-for-developers-project-386/actions/workflows/hexlet-check.yml/badge.svg)](https://github.com/codersam/ai-for-developers-project-386/actions)

### End-to-end tests

Playwright E2E suite that exercises the production Docker image (Postgres + Spring Boot + React SPA) against the golden booking path. See [e2e/README.md](e2e/README.md) for prerequisites and how to run.

### Deployed to Render.com

Public link: https://hexlet-calendar-codersam.onrender.com/

### Development path

The project was built in two phases against a single OpenAPI 3.1 contract ([backend/spec/openapi.yaml](backend/spec/openapi.yaml)). The frontend was built first against a Prism mock so the contract could be exercised without a backend; the backend was then implemented to satisfy the same spec, and finally the two were packaged together and deployed.

Each step was planned in a `STEP*.md` file before code was written, so the docs read as a chronological design journal — what we considered, what we picked, what we rejected, and (from STEP6 onward) the deviations and lessons learned during implementation.

#### Frontend ([frontend/docs/](frontend/docs/))

Vite + React + Mantine, talking to a Prism mock through `/api`.

- [STEP1.md](frontend/docs/STEP1.md) — first runnable checkpoint: app shell, routing, mock wiring, OpenAPI client generation.
- [STEP2.md](frontend/docs/STEP2.md) — guest happy-path booking flow (event-type selection, slot picker, booking form, confirmation).
- [STEP3.md](frontend/docs/STEP3.md) — owner flow: list and create event types, view scheduled events.
- [SUMMARY1-3.md](frontend/docs/SUMMARY1-3.md) — alignment review across the three frontend steps.

#### Backend ([backend/docs/](backend/docs/))

Spring Boot 3.4 / Java 21, contract-first via the OpenAPI generator, Postgres 16 + Flyway, JPA, MapStruct.

- [STEP1.md](backend/docs/STEP1.md) — scaffolding: Gradle project, OpenAPI generator, Compose-driven local Postgres, baseline app boots green.
- [STEP2.md](backend/docs/STEP2.md) — persistence layer + `EventType` endpoints; first migration; first MapStruct wiring.
- [STEP3.md](backend/docs/STEP3.md) — pure `SlotMath` plus the availability endpoint; DST and timezone edge-case suite.
- [STEP4.md](backend/docs/STEP4.md) — booking lifecycle, exclusion-constraint collision rule, typed `Error` body via `@RestControllerAdvice`.
- [STEP5.md](backend/docs/STEP5.md) — cleanup and polish: actuator, request logging, README.
- [REVIEW3.md](backend/docs/REVIEW3.md) — backend test-coverage review used to drive the polish in STEP5.
- [STEP6.md](backend/docs/STEP6.md) — single deployable Docker image: multi-stage build that bakes the React `dist/` into the Spring boot JAR's static resources.
- [STEP7.md](backend/docs/STEP7.md) — end-to-end Playwright suite that drives the production image against the golden booking path.
- [STEP8.md](backend/docs/STEP8.md) — Render.com deploy via the Render MCP server (managed Postgres + Docker web service); captures the JDBC-URL gotcha and the database-name surprise observed during the live deploy.
- [STEP9.md](backend/docs/STEP9.md) — alternative single-container deploy: a slim image that bundles PostgreSQL alongside the Spring Boot app, boots with only `PORT` set, and persists via a declared `VOLUME`.
