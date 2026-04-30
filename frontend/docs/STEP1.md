# Tactics — Step 1 (first runnable checkpoint)

Concrete first steps to go from empty `frontend/` to "guest can see event types from Prism in the browser." Stop after step 8 — that's the first runnable checkpoint. See [PLAN.md](PLAN.md) for the strategic plan.

## 1. Scaffold Vite app inside `frontend/`
The folder already exists, so scaffold into a temp dir and merge — Vite refuses non-empty targets.
```bash
cd /Users/mseven/Projects/Hexlet/ai-for-developers-project-386
npm create vite@latest frontend-tmp -- --template react-ts
rsync -a frontend-tmp/ frontend/
rm -rf frontend-tmp
cd frontend && npm install
```
Verify `frontend/spec/` is untouched.

## 2. Install runtime deps
```bash
npm i @mantine/core @mantine/hooks @mantine/dates @mantine/form @mantine/notifications dayjs react-router @tanstack/react-query openapi-fetch
```

## 3. Install dev deps
```bash
npm i -D postcss postcss-preset-mantine postcss-simple-vars openapi-typescript @stoplight/prism-cli concurrently
```
Note: pin TypeScript to `5.9.3` first (`npm i -D typescript@5.9.3`) — `openapi-typescript` peer range still says `^5.x`.

## 4. Mantine setup files
- Create `postcss.config.cjs` from Mantine v9 docs (`postcss-preset-mantine` + `postcss-simple-vars` with breakpoint vars).
- Edit `index.html`: set `<title>` to `Calendar`. `<ColorSchemeScript />` is rendered from `main.tsx` instead of inline.
- Replace generated `src/App.css` / boilerplate with a minimal `App.tsx` returning a Mantine `AppShell`. Delete `src/index.css`, `src/assets/`, and `public/icons.svg`.

## 5. Wire providers in `src/main.tsx`
Order: `MantineProvider` → `Notifications` → `QueryClientProvider` → `BrowserRouter` → `<App />`. Add the three CSS imports:
```ts
import "@mantine/core/styles.css";
import "@mantine/dates/styles.css";
import "@mantine/notifications/styles.css";
```

## 6. Generate the API types and client
- Add scripts to `package.json`:
  - `"gen:api": "openapi-typescript spec/openapi.yaml -o src/api/schema.ts"`
  - `"mock": "prism mock spec/openapi.yaml -p 4010 --dynamic"`
  - `"vite": "vite"`
  - `"dev": "concurrently -k -n mock,vite -c yellow,cyan \"npm:mock\" \"vite\""`
- Run `npm run gen:api`.
- Create `src/api/client.ts` exporting `createClient<paths>({ baseUrl: "/api" })`.

## 7. Vite proxy
In `vite.config.ts` add:
```ts
server: { proxy: { "/api": { target: "http://127.0.0.1:4010", changeOrigin: true, rewrite: p => p.replace(/^\/api/, "") } } }
```

## 8. First end-to-end vertical slice — guest event types list
- `src/lib/timezone.ts`: `getClientTimezone()` wrapping `Intl.DateTimeFormat().resolvedOptions().timeZone`.
- `src/api/hooks.ts`: write `useEventTypes()` (TanStack Query wrapping `api.GET("/calendar/event_types")`).
- `src/components/EventTypeCard.tsx`: Mantine card (title, duration badge, description, "Book" button → `/book/:id`).
- `src/pages/guest/EventTypesPage.tsx`: render a Mantine `SimpleGrid` of `EventTypeCard`s from the hook, with `Loader` and error `Alert`.
- `src/routes/index.tsx`: a single route `/` → `EventTypesPage`.
- Run `npm run dev`, open `http://localhost:5173/`, confirm Prism-generated event types render.
