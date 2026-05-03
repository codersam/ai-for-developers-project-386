# STEP7 — End‑to‑end tests for the main use case (Playwright)

## Context

After STEP6 the repo ships a single deployable Docker image: Spring Boot + React `dist/` baked into static resources, with the SPA fallback at [backend/src/main/java/com/hexlet/calendar/web/SpaFallbackController.java](../src/main/java/com/hexlet/calendar/web/SpaFallbackController.java) and `compose.prod.yaml` orchestrating Postgres + the app on `:8080`. The backend has 48 unit/integration tests; the React app has **zero** automated tests today.

STEP7 closes that gap with a Playwright E2E suite that exercises the **golden path** end‑to‑end against the real production image — not a mock, not the dev proxy. This validates that the deployable artefact (the same image you would push to a registry) actually works: SPA routes resolve, `/api/*` reaches Spring, Flyway has run, and the React UI plus Spring controllers plus Postgres exclusion constraint behave correctly together. The two questions we answer with this suite:

1. Can an Owner create an event type, a Guest book one of its slots, and the Owner see that booking in the upcoming list?
2. Can the user reach every SPA route (deep link, refresh) without the SPA fallback regressing?

Per user direction: tests live at the **repo root in a new `e2e/` folder** (top‑level package.json), and run against the **production image** brought up via `compose.prod.yaml`.

## Prep — what to install / prepare in advance

Before running this plan you need:

1. **Docker Desktop running** (compose.prod.yaml is the test target).
2. **Node ≥ 20** on PATH for the `e2e/` package (matches the frontend's Node version; verify with `node -v`).
3. **`.env` at repo root** with at minimum `POSTGRES_PASSWORD` set — `compose.prod.yaml:10,34` declares it required (`${POSTGRES_PASSWORD:?...}`). Copy [.env.example](../../.env.example) to `.env` and fill in a real value. Without this, `docker compose -f compose.prod.yaml up` aborts.
4. **Free TCP ports**: `8080` (app) and `5432` is *not* exposed by `compose.prod.yaml` so no clash there, but `8080` must be free.
5. **Disk + time budget**: first `docker compose ... up --build` runs both the Node and Gradle multi-stage builds — expect ~3–5 min cold, seconds warm.
6. **Playwright MCP server (recommended for implementation, not runtime).** [microsoft/playwright-mcp](https://github.com/microsoft/playwright-mcp) lets Claude Code drive a real browser during the implementation phase — navigate to the running app, snapshot the accessibility tree, and confirm exact `role`/`name`/`label` values for the selectors below. Install once with `claude mcp add playwright npx '@playwright/mcp@latest'` (or via the Claude Code MCP config UI). Then during Step 4, instead of guessing the Mantine DatePicker / Modal / time-slot selectors, Claude can `browser_navigate` to `http://localhost:8080` against the running stack and `browser_snapshot` to read off the real roles. **This is purely a dev-time tool** — the test suite itself runs through `@playwright/test`, so CI does not need the MCP server.

Nothing else needs to be installed manually — the plan installs Playwright + the Chromium browser as part of Step 1.

## Decisions (resolved)

| Question | Decision | Why |
|---|---|---|
| Target env | Production image via `compose.prod.yaml` | Validates the actual deployable; SPA fallback + `/api` context-path are real, not mocked. |
| Test location | Top-level `e2e/` (own `package.json`) | Tests treat the system as black box — they don't belong to `frontend/`. Keeps the JS test toolchain off the React build. |
| Browsers | **Chromium only** for STEP7 | Mantine renders identically across browsers; Chromium covers ~all real bugs. Firefox/WebKit can be added later for `npx playwright test --project=firefox` without code changes. |
| Stack lifecycle | Playwright `globalSetup`/`globalTeardown` shells out to `docker compose ... up -d --build --wait` and `down -v` | `--wait` blocks on the healthchecks already defined in `compose.prod.yaml` (`/api/actuator/health`); `down -v` wipes the Postgres volume so each `npx playwright test` run starts from an empty DB. |
| DB seed strategy | None — tests create what they need via the UI | The first thing the main-flow test does is create an event type as the Owner. Mirrors how a real user would set the system up. |
| Selector strategy | `getByRole` / `getByLabel` / `getByText` only — **no test-ids added** | The frontend has no `data-testid` today. Adding them is a frontend change unrelated to E2E correctness. Mantine renders semantic roles and labels that Playwright targets cleanly. |

## Folder layout (new files only)

```
e2e/
  package.json                    # @playwright/test + scripts
  tsconfig.json                   # extends @tsconfig/node20 (or hand-rolled)
  playwright.config.ts            # baseURL, globalSetup, globalTeardown, projects
  .gitignore                      # test-results/, playwright-report/, node_modules/
  README.md                       # how to run, what to install (mirrors prep section)
  global-setup.ts                 # docker compose up -d --build --wait
  global-teardown.ts              # docker compose down -v
  fixtures/
    test-data.ts                  # unique-name helper, common page-object selectors
  tests/
    main-flow.spec.ts             # the golden path (owner→guest→owner)
    spa-routing.spec.ts           # deep links + refresh behave
```

## Step-by-step plan

### Step 1 — Scaffold `e2e/`

Create `e2e/package.json` with:

```json
{
  "name": "calendar-e2e",
  "private": true,
  "version": "0.0.0",
  "scripts": {
    "test:e2e": "playwright test",
    "test:e2e:headed": "playwright test --headed",
    "test:e2e:ui": "playwright test --ui",
    "report": "playwright show-report"
  },
  "devDependencies": {
    "@playwright/test": "^1.50.0",
    "@types/node": "^24.12.2",
    "typescript": "^5.9.3"
  }
}
```

Then `cd e2e && npm install && npx playwright install --with-deps chromium`. The `--with-deps` flag pulls native Linux deps if on Linux; on macOS it's a no-op.

### Step 2 — `playwright.config.ts`

```ts
import { defineConfig, devices } from '@playwright/test';

const reuseStack = process.env.E2E_REUSE_STACK === '1';

export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,           // serial: tests share one Postgres
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  globalSetup: reuseStack ? undefined : './global-setup.ts',
  globalTeardown: reuseStack ? undefined : './global-teardown.ts',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
});
```

`E2E_REUSE_STACK=1` lets a developer keep `compose.prod.yaml` running between iterations and re-run `npm run test:e2e` instantly. CI uses the default (full bring-up + teardown).

### Step 3 — `global-setup.ts` / `global-teardown.ts`

`global-setup.ts`:
- Resolve repo root from `__dirname` (`path.resolve(__dirname, '..')`).
- `execSync('docker compose -f compose.prod.yaml up -d --build --wait', { cwd: repoRoot, stdio: 'inherit' })`.
- `--wait` blocks until both services report `healthy` per their healthchecks (`pg_isready` for Postgres, `/api/actuator/health` for the app — both already defined in `compose.prod.yaml:15-20,41-46`).
- After `--wait` returns, do an extra fetch of `${baseURL}/api/actuator/health` and assert `status === 'UP'` to fail fast with a readable error if anything regressed.

`global-teardown.ts`:
- `execSync('docker compose -f compose.prod.yaml down -v', { cwd: repoRoot, stdio: 'inherit' })` — `-v` wipes `calendar-pgdata-prod`, so the next run starts from an empty schema.

These shell out to `docker compose` rather than using a Node Docker SDK to keep deps minimal and behavior identical to what a developer types by hand.

### Step 4 — `tests/main-flow.spec.ts` (the golden path)

One spec, one `test()`, sequential — because the use case **is** sequential ("first the owner creates a type, then the guest books, then the owner sees it"). Splitting it into three independent tests would force each test to recreate the prerequisite state, which buys nothing on a single-worker run.

Rough script:

```ts
import { test, expect } from '@playwright/test';

test('owner creates event type, guest books a slot, owner sees the booking', async ({ page }) => {
  const eventName = `Intro Call ${Date.now()}`;   // unique per run

  // --- Owner creates the event type ---
  await page.goto('/admin/event-types');
  await page.getByRole('button', { name: 'New event type' }).click();
  await page.getByLabel('Name').fill(eventName);
  await page.getByLabel('Description').fill('A short intro chat for E2E.');
  // Duration NumberInput defaults to 30 — leave it.
  await page.getByRole('button', { name: 'Create' }).click();
  await expect(page.getByText(eventName)).toBeVisible();

  // --- Guest books a slot ---
  await page.getByRole('link', { name: 'Book' }).click();   // header → /
  await expect(page).toHaveURL('/');
  const card = page.getByText(eventName).locator('..');     // EventTypeCard wrapping
  await card.getByRole('link', { name: 'Book' }).click();   // /book/:eventTypeId

  // Pick the first enabled day on the Mantine DatePicker.
  // Mantine renders day buttons with role=button; disabled ones have aria-disabled="true".
  const firstAvailableDay = page.locator(
    '[role="button"][data-mantine-stop-propagation], button[aria-disabled="false"]'
  ).filter({ hasText: /^\d+$/ }).first();
  // (Refine this selector during implementation by inspecting the rendered DOM —
  //  Mantine v9 day buttons may use slightly different attributes.)
  await firstAvailableDay.click();

  // Pick the first time slot button (rendered after a day is selected).
  await page.getByRole('button', { name: /^\d{2}:\d{2}$/ }).first().click();

  // Booking modal.
  await expect(page.getByText('Confirm your booking')).toBeVisible();
  await page.getByLabel('Name').fill('E2E Guest');
  await page.getByLabel('Email').fill('e2e-guest@example.com');
  await page.getByLabel("What's this meeting about?").fill('Sync');
  await page.getByLabel(/Notes/).fill('Created by Playwright.');
  await page.getByRole('button', { name: 'Book', exact: true }).click();

  // Confirmation page.
  await expect(page).toHaveURL(/\/book\/.+\/confirmed\/.+/);
  await expect(page.getByText("You're booked!")).toBeVisible();
  await expect(page.getByText(eventName)).toBeVisible();

  // --- Owner sees the booking ---
  await page.getByRole('link', { name: 'Upcoming' }).click();
  await expect(page).toHaveURL('/admin/bookings');
  await expect(page.getByText('Sync')).toBeVisible();              // subject
  await expect(page.getByText('e2e-guest@example.com')).toBeVisible();
  await expect(page.getByText(eventName)).toBeVisible();
});
```

The DatePicker selector and one or two of the form labels above are best-guesses. Two ways for the implementer to confirm them:

- **Preferred — Playwright MCP** (see Prep §6): with the prod image up, Claude calls `browser_navigate` to each page and `browser_snapshot` to read the accessibility tree directly, then writes the spec with verified selectors in one pass. Avoids the edit→run→fail→edit loop entirely for selector discovery.
- **Fallback — manual codegen**: `npx playwright test --headed` to watch a failing run, or `npx playwright codegen http://localhost:8080` to record a session. Slower but always available.

### Step 5 — `tests/spa-routing.spec.ts` (smoke test for the SPA fallback)

A short second spec that locks in STEP6's exit criterion (deep links don't 404):

```ts
import { test, expect } from '@playwright/test';

const deepLinks = [
  '/',
  '/admin/event-types',
  '/admin/bookings',
];

for (const path of deepLinks) {
  test(`deep link ${path} loads the SPA shell`, async ({ page }) => {
    const res = await page.goto(path);
    expect(res?.status()).toBe(200);
    // The header is rendered by App.tsx for every route.
    await expect(page.getByRole('link', { name: 'Book' })).toBeVisible();
  });
}

test('hard refresh on an admin route does not 404', async ({ page }) => {
  await page.goto('/admin/event-types');
  await page.reload();
  await expect(page.getByRole('link', { name: 'Event types' })).toBeVisible();
});
```

This is cheap insurance: if anyone breaks `SpaFallbackController` later, this fails immediately.

### Step 6 — `e2e/README.md`

Short doc covering: prerequisites (mirror of the Prep section), how to run (`npm install`, `npx playwright install chromium`, `npm run test:e2e`), the `E2E_REUSE_STACK=1` shortcut, where the HTML report goes, and how to view a trace from a failure.

### Step 7 — Root-repo touch-ups

- Append `e2e/node_modules/`, `e2e/test-results/`, `e2e/playwright-report/` to the repo `.gitignore`.
- Add a one-line "End-to-end tests" section to the repo root README pointing at `e2e/README.md`.

## Critical files to be created

- [e2e/package.json](../../e2e/package.json)
- [e2e/playwright.config.ts](../../e2e/playwright.config.ts)
- [e2e/global-setup.ts](../../e2e/global-setup.ts)
- [e2e/global-teardown.ts](../../e2e/global-teardown.ts)
- [e2e/tests/main-flow.spec.ts](../../e2e/tests/main-flow.spec.ts) — the golden path
- [e2e/tests/spa-routing.spec.ts](../../e2e/tests/spa-routing.spec.ts) — SPA fallback smoke
- [e2e/README.md](../../e2e/README.md)
- [e2e/.gitignore](../../e2e/.gitignore)
- [e2e/tsconfig.json](../../e2e/tsconfig.json)

No existing files are modified except the repo-root `.gitignore` and `README.md`.

## What this suite does *not* cover (explicit non-goals)

- Cross-browser (Firefox / WebKit) — Chromium only.
- Concurrency / collision (already covered exhaustively by `ConcurrencyIT` at the JVM level).
- Visual regression — out of scope; would need a snapshot library and stable rendering.
- Mobile viewports — desktop only.
- API-direct tests — the backend's IT suite already covers `/api/*` shape and errors. E2E is for the user-visible flow.
- Authentication — the app has none.

These are deliberate exclusions to keep STEP7 small. Each can be added later as its own spec file without touching the existing scaffolding.

## Verification

End-to-end after implementation:

1. `cp .env.example .env` and set `POSTGRES_PASSWORD=<anything>`.
2. `cd e2e && npm install && npx playwright install --with-deps chromium`.
3. `npm run test:e2e` — should:
   - Build and start the prod image (~3–5 min cold).
   - Wait for both healthchecks to report `healthy`.
   - Run the two specs against `http://localhost:8080`.
   - Tear the stack down (`down -v`).
   - Print `2 passed`.
4. `npm run report` — opens the HTML report in the browser.
5. Sanity: `E2E_REUSE_STACK=1 docker compose -f compose.prod.yaml up -d --build --wait`, then `E2E_REUSE_STACK=1 npm run test:e2e` reruns the suite in seconds without rebuilding.
6. Force a failure (e.g., temporarily change a label expectation) and confirm:
   - The `playwright-report/` HTML opens with the failure.
   - `test-results/` contains a screenshot + video + trace zip for the failed test.
7. Stop the stack: `docker compose -f compose.prod.yaml down -v`.

## Implementation summary

Scaffolded the full `e2e/` package per the plan, with three deliberate selector tightenings vs. the draft script (the plan flagged the DatePicker / time-slot selectors as best-guesses).

### Files created

- [e2e/package.json](../../e2e/package.json) — `@playwright/test` **^1.59.1** (upgraded from the planned `^1.50.0` per user request), `@types/node`, `typescript`. Scripts: `test:e2e`, `test:e2e:headed`, `test:e2e:ui`, `report`.
- [e2e/tsconfig.json](../../e2e/tsconfig.json) — hand-rolled (ES2022 / ESNext / Bundler resolution / strict), `types: ["node"]`. Replaces `@tsconfig/node20` to avoid an extra dep.
- [e2e/.gitignore](../../e2e/.gitignore) — `node_modules/`, `test-results/`, `playwright-report/`.
- [e2e/playwright.config.ts](../../e2e/playwright.config.ts) — verbatim from Step 2.
- [e2e/global-setup.ts](../../e2e/global-setup.ts) — `docker compose ... up -d --build --wait` then a `fetch` health re-check that asserts `status === 'UP'`.
- [e2e/global-teardown.ts](../../e2e/global-teardown.ts) — `docker compose ... down -v`.
- [e2e/tests/main-flow.spec.ts](../../e2e/tests/main-flow.spec.ts) — golden path (owner → guest → owner).
- [e2e/tests/spa-routing.spec.ts](../../e2e/tests/spa-routing.spec.ts) — deep-link + reload smoke.
- [e2e/fixtures/test-data.ts](../../e2e/fixtures/test-data.ts) — `uniqueName(prefix)` helper.
- [e2e/README.md](../../e2e/README.md) — prerequisites, install, run, `E2E_REUSE_STACK=1` shortcut, traces.

### Selector corrections vs. the draft in Step 4

| Concern | Draft (plan §4) | Shipped | Why |
|---|---|---|---|
| Time-slot regex | `/^\d{2}:\d{2}$/` | `/^\d{2}:\d{2}:\d{2}$/` | OpenAPI emits `HH:mm:ss` (see `backend/spec/openapi.yaml:95–131`); `SlotPicker.tsx:53` renders `t.timeStart` verbatim, so the button text is `09:00:00`. The plan's regex matched zero buttons. |
| Day-button selector | `[role="button"][data-mantine-stop-propagation], button[aria-disabled="false"]` filtered by `^\d+$` | `table button:not([data-disabled])` filtered by `^\d{1,2}$` | Mantine v9's `DatePicker` renders days inside a `<table>` and marks excluded days with `data-disabled`, not `aria-disabled`. The new selector matches the actual DOM and naturally skips disabled days. |
| EventTypeCard scoping | `page.getByText(eventName).locator('..')` | `page.locator('.mantine-Card-root').filter({ hasText: eventName })` | The text is wrapped in `<Group>` (see `EventTypeCard.tsx:11–18`); a single `..` only walks up to the `<Group>`, which doesn't contain the "Book" link. Using `.mantine-Card-root` + `hasText` walks straight to the card. |
| Modal "Name" disambiguation | bare `getByLabel('Name')` | `getByRole('dialog', { name }).getByLabel('Name')` for both modals | `EventTypesAdminPage` and `BookEventPage` both render a `<TextInput label="Name" />`; scoping by the dialog's accessible name keeps the test sharp even if the admin modal lingers. |
| Final URL matchers | `toHaveURL('/')` and `toHaveURL('/admin/bookings')` | regex variants (`/\/$/`, `/\/admin\/bookings$/`) | Playwright's strict-mode string match required a fully resolved URL including `baseURL`; regex anchors are simpler and equivalent. |

### Root-repo touch-ups

- `.gitignore` — appended `e2e/test-results/` and `e2e/playwright-report/`. The pre-existing `node_modules/` rule (no leading slash) already covers `e2e/node_modules/`, so it was *not* re-added (deviation from Step 7's "append all three" — keeps `.gitignore` minimal).
- `README.md` — added an "End-to-end tests" section pointing at `e2e/README.md`.

### Fixes that emerged from the first real run

The first `npm run test:e2e` against the prod image surfaced three Playwright strict-mode collisions; all are now fixed:

1. **`getByText(eventName)` after Create** matched both the new `EventTypeCard` *and* the Mantine success notification (`title: "Created", message: eventTypeName` from `EventTypesAdminPage.tsx:44–48`). Replaced with `page.locator('.mantine-Card-root').filter({ hasText: eventName })`.
2. **Header `Book` link click** matched both the header `NavLink` *and* every `EventTypeCard`'s "Book" button-as-link (cards persist across specs because tests share one Postgres). Both this click and the deep-link `/` assertion in `spa-routing.spec.ts` are now scoped via `page.getByRole('banner')`.
3. **`getByText(eventName)` on the confirmation and `/admin/bookings` pages** still raced against the *previous* "Created" toast (Mantine's default `autoClose: 4000ms` outlasts the booking flow). Scoped to `page.getByRole('main')` for every post-booking assertion.

### Verification done locally

- `npm install` in `e2e/` → 0 vulnerabilities; `npx playwright --version` → `1.59.1`.
- `npx tsc --noEmit` → clean.
- `npm run test:e2e` (full bring-up + run + teardown of `compose.prod.yaml`) → **5 passed** in ~26 s warm. The prod image was already built from a prior run; first cold run is the documented 3–5 min.
