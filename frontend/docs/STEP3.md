# Tactics — Step 3 (owner flow)

## Context

STEP 1 stood up the Vite + Mantine shell and the guest event-types list. STEP 2 finished the guest happy-path booking (slot picker → modal → confirmation). The remaining slice from [../CLAUDE.md](../CLAUDE.md) is the **Owner flow** (steps 6–7 of the implementation order):

- `/admin/event-types` — list existing event types and create new ones.
- `/admin/bookings` — show all upcoming scheduled events in one list.

The header nav links for both are already wired in [../src/App.tsx](../src/App.tsx) (lines 15–20), but they currently route nowhere. The `src/pages/owner/` directory exists but is empty. STEP 3 fills both pages, adds the two missing API hooks, and registers the routes.

Stop after step 7 — that is the third runnable checkpoint. After this, the brief in [../spec/task.txt](../spec/task.txt) is fully exercisable end-to-end against Prism. See [../CLAUDE.md](../CLAUDE.md) for the strategic plan.

## Files to be modified / created

Modified:
- [../src/api/hooks.ts](../src/api/hooks.ts) — add `useCreateEventType` and `useScheduledEvents`.
- [../src/routes/index.tsx](../src/routes/index.tsx) — add the two `/admin/*` routes.

Created:
- `src/pages/owner/EventTypesAdminPage.tsx`
- `src/pages/owner/ScheduledEventsPage.tsx`

No changes to [../src/App.tsx](../src/App.tsx) (nav already present), no new dependencies, no schema regeneration needed.

## 1. Extend `src/api/hooks.ts` with two more hooks

Mirror the existing conventions in [../src/api/hooks.ts](../src/api/hooks.ts) (see `useCreateScheduledEvent` lines 48–61 for the mutation shape, `useAvailableSlots` lines 30–44 for the timezone-keyed query shape).

Schema facts verified in [../src/api/schema.ts](../src/api/schema.ts):

- `POST /calendar/event_types` request body is `components["schemas"]["CreateEventType"]` (schema.ts line 175), a write-only model with `{ eventTypeName, description, durationMinutes }` — no id. The response is the full `EventType`. Use `CreateEventType` directly for the hook body; no cast or `Omit<>` needed.
- `GET /calendar/scheduled_events` requires the `clientTimeZone` query param (line 235) and returns `ScheduledEvent[]` (line 249). `ScheduledEvent` now carries `subject` and `notes` (lines 111–112) — both are required by the spec and become the primary display fields on the owner page (see §3). Reuse `getClientTimezone()` from [../src/lib/timezone.ts](../src/lib/timezone.ts).

```ts
export type CreateEventTypeBody = components["schemas"]["CreateEventType"];

export function useCreateEventType() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CreateEventTypeBody) => {
      const { data, error } = await api.POST("/calendar/event_types", { body });
      if (error) throw error;
      return data!;
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["event_types"] });
    },
  });
}

export function useScheduledEvents() {
  const clientTimeZone = getClientTimezone();
  return useQuery({
    queryKey: ["scheduled_events", clientTimeZone],
    queryFn: async () => {
      const { data, error } = await api.GET("/calendar/scheduled_events", {
        params: { query: { clientTimeZone } },
      });
      if (error) throw error;
      return data ?? [];
    },
  });
}
```

The query key `["scheduled_events", ...]` matches the invalidation already wired in `useCreateScheduledEvent` ([../src/api/hooks.ts](../src/api/hooks.ts) line 57) — no change needed there.

## 2. EventTypesAdminPage — list + create modal

File: `src/pages/owner/EventTypesAdminPage.tsx`. Reuses `useEventTypes()` from STEP 1 for the list.

Layout: `Title` + "New event type" button (right-aligned) → `Stack` of cards. Reuse the visual rhythm of [../src/components/EventTypeCard.tsx](../src/components/EventTypeCard.tsx) but render inline without the "Book" button — extracting an admin-variant component is unwarranted for one call site. Loading / error / empty states copy verbatim from [../src/pages/guest/EventTypesPage.tsx](../src/pages/guest/EventTypesPage.tsx) lines 8–22.

Form: inline `useForm` (matches [../src/pages/guest/BookEventPage.tsx](../src/pages/guest/BookEventPage.tsx) lines 40–46). Do **not** extract `EventTypeForm` to a shared component — single use site, no edit flow yet, no second consumer.

```tsx
const form = useForm<CreateEventTypeBody>({
  initialValues: { eventTypeName: "", description: "", durationMinutes: 30 },
  validate: {
    eventTypeName: (v) => (v.trim().length < 2 ? "Required" : null),
    description: (v) => (v.trim().length < 1 ? "Required" : null),
    durationMinutes: (v) => (v > 0 && v <= 240 ? null : "1–240"),
  },
});

const create = useCreateEventType();
const [opened, { open, close }] = useDisclosure(false);

const handleSubmit = form.onSubmit((values) => {
  create.mutate(values, {
    onSuccess: () => {
      notifications.show({ color: "green", title: "Created", message: values.eventTypeName });
      form.reset();
      close();
    },
    onError: (e) =>
      notifications.show({ color: "red", title: "Couldn't create", message: String(e) }),
  });
});
```

Modal shell: `Modal` from `@mantine/core` (not `@mantine/modals`), driven by `useDisclosure`. Fields: `TextInput` (name), `Textarea` (description), `NumberInput` (durationMinutes, min=1, max=240, step=15). Footer: `Cancel` + `Create` buttons, `loading={create.isPending}` on the submit button.

## 3. ScheduledEventsPage — flat list grouped by date

File: `src/pages/owner/ScheduledEventsPage.tsx`. Reads both `useScheduledEvents()` and `useEventTypes()` so it can show the event-type name as secondary metadata next to each booking's `subject` (verified shape at [../src/api/schema.ts](../src/api/schema.ts) lines 104–115).

```tsx
const { data: events = [], isLoading, error } = useScheduledEvents();
const { data: types = [] } = useEventTypes();
const typeById = useMemo(
  () => new Map(types?.map((t) => [t.eventTypeId, t]) ?? []),
  [types],
);
```

Grouping: in a `useMemo`, sort by `utcDateStart` ascending, then group by local-date-string using `dayjs(e.utcDateStart).tz(getClientTimezone()).format("YYYY-MM-DD")` from [../src/lib/dayjs.ts](../src/lib/dayjs.ts) — `utc` and `timezone` plugins are already extended there. Render each group as a `Title order={4}` (the local date) followed by a Mantine `Table` with columns:

- **Time** — `dayjs(utcDateStart).tz(...).format("HH:mm")`.
- **Subject** — `e.subject` in `<Text fw={500}>`. Render `e.notes` underneath as `<Text size="xs" c="dimmed" lineClamp={2}>` so long notes don't wreck the table; full text on hover via `title={e.notes}`.
- **Event type** — `typeById.get(e.eventTypeId)?.eventTypeName` with a fallback `<Text c="dimmed">unknown</Text>` for stale ids.
- **Guest** — name + email stacked (`<Stack gap={0}>`).
- **Duration** — `<Badge variant="light">{e.durationMinutes} min</Badge>`.

`subject` is the primary identifier (it's what the guest typed); the joined event-type name is supporting context (which template was used).

Loading / error / empty states match [../src/pages/guest/EventTypesPage.tsx](../src/pages/guest/EventTypesPage.tsx). Empty state: `<Text c="dimmed">No upcoming bookings.</Text>` — Prism is stateless so this won't show, but a real backend will need it.

Owner timezone is the same as the browser's TZ (no separate owner profile in the brief). Surface it once at the top via the same `<TextInput readOnly>` pattern used in [../src/pages/guest/BookEventPage.tsx](../src/pages/guest/BookEventPage.tsx) lines 114–119, so the user understands which TZ the times are rendered in.

## 4. Wire the routes

Edit [../src/routes/index.tsx](../src/routes/index.tsx):

```tsx
<Routes>
  <Route path="/" element={<EventTypesPage />} />
  <Route path="/book/:eventTypeId" element={<BookEventPage />} />
  <Route path="/book/:eventTypeId/confirmed" element={<BookingConfirmedPage />} />
  <Route path="/admin/event-types" element={<EventTypesAdminPage />} />
  <Route path="/admin/bookings" element={<ScheduledEventsPage />} />
</Routes>
```

No 404 route — React Router silently renders nothing on unknown paths, which matches what the guest routes do today.

## 5. Smoke-test the type plumbing

`npx tsc -b` (or rely on Vite's overlay). Specific things to watch:

- **Carry-over from STEP 2 — fix before STEP 3 compiles cleanly.** The spec now requires `subject` and `notes` on `CreateScheduledEvent` (schema.ts lines 84–85). [../src/pages/guest/BookEventPage.tsx](../src/pages/guest/BookEventPage.tsx) currently only sends `{ guestName, guestEmail }` — TypeScript will fail there. STEP 3 inherits a clean tree, so add the two `Textarea`/`TextInput` fields to the booking modal form and pass them through `create.mutate(...)` before starting on the owner pages.
- Mantine v9 `NumberInput` `onChange` yields `string | number`; `form.getInputProps("durationMinutes")` handles the conversion — don't re-implement.
- `dayjs.tz(...)` requires both `utc` and `timezone` plugins; both are extended in [../src/lib/dayjs.ts](../src/lib/dayjs.ts) — import `dayjs` from there, not from `"dayjs"`.

## 6. Polish (carry-over from CLAUDE.md step 7)

- Loading / error / empty states already covered above.
- A short "Owner" subtitle banner is unnecessary — header nav already differentiates the section.
- Basic responsive check: admin pages render fine at narrow widths because they use `Stack` + a single `Table`. No grid breakpoint logic needed.

## 7. Verification — runnable demo

```bash
cd frontend && npm run dev
```

At `http://localhost:5173/`:

1. Guest regression check: event types list still loads; booking flow still works end-to-end (STEP 1 + STEP 2 unchanged).
2. Click **Event types** in the header → `/admin/event-types`: Prism returns a list of event types. Verify cards render with name, description, and a duration badge but **no "Book" button** (this is the admin variant).
3. Click **New event type** → modal opens. Enter name, description, duration. Submit. Green toast fires; modal closes; the list does **not** grow (Prism is stateless — same caveat called out in [../CLAUDE.md](../CLAUDE.md) under "Mocking via Prism"). Open DevTools → Network → confirm a `POST /api/calendar/event_types` was sent and a 200 came back.
4. Validation check: try submitting with name <2 chars or duration 0 / 300 — Mantine inline errors fire; no network call.
5. Click **Upcoming** → `/admin/bookings`: table groups bookings by local date; each row shows time (in the browser's TZ), `subject` with `notes` clamped beneath, event-type name (joined from cached `useEventTypes`), guest name + email stacked, and a duration badge. Hover a row's notes to see the full text via the native `title` tooltip. The owner-timezone read-only field at the top reflects `Intl.DateTimeFormat().resolvedOptions().timeZone`.
6. Open DevTools → Network → navigate **Book → Event types → Upcoming → Book** in sequence. Confirm `event_types` is fetched **once** (cache shared across guest + admin pages via `["event_types"]` key) and `scheduled_events` is fetched **once per admin visit** with the `clientTimeZone` query param attached.
7. `npm run build && npm run preview` — production build succeeds; preview server renders both new admin pages.

Once a real backend is available, the stateless-mock caveat disappears and the create + list cycle round-trips correctly. No frontend code change required at that point.
