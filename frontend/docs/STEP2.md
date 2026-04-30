# Tactics — Step 2 (guest happy-path booking)

Concrete next moves from "guest sees event types" (end of [STEP1.md](STEP1.md)) to "guest can book end-to-end against Prism." Stop after step 9 — that's the second runnable checkpoint. Wires the two outstanding guest-side endpoints (`GET …/available_slots`, `POST /scheduled_events`) and locks in the shared utilities (`dayjs` plugins, 14-day window, slot-picker contract) that Owner pages will reuse in STEP 3. See [PLAN.md](PLAN.md) for the strategic plan.

## 1. Configure dayjs once with utc + timezone

`@mantine/dates` already pulls `dayjs` in. Centralise the plugin extension so every consumer shares one configured instance.

File: `src/lib/dayjs.ts`

```ts
import dayjs from "dayjs";
import utc from "dayjs/plugin/utc";
import timezone from "dayjs/plugin/timezone";

dayjs.extend(utc);
dayjs.extend(timezone);

export { dayjs };
```

No `customParseFormat` needed — Prism returns `YYYY-MM-DD` and `HH:mm[:ss]`, both natively parseable.

## 2. 14-day window helpers

File: `src/lib/bookingWindow.ts`. Pure helpers, no state.

```ts
import { dayjs } from "./dayjs";

export const BOOKING_WINDOW_DAYS = 14;

export function getBookingWindow(now = dayjs()) {
  const min = now.startOf("day");
  const max = min.add(BOOKING_WINDOW_DAYS - 1, "day");
  return { min: min.toDate(), max: max.toDate() };
}

export function isWithinBookingWindow(dateISO: string, now = dayjs()) {
  const d = dayjs(dateISO).startOf("day");
  const { min, max } = getBookingWindow(now);
  return !d.isBefore(min) && !d.isAfter(max);
}
```

`SlotPicker` wires `min`/`max` to `DatePicker`; `BookEventPage` defensively filters `slotsPerDay` through `isWithinBookingWindow` before passing it down.

## 3. Extend `src/api/hooks.ts`

Currently exports only `useEventTypes`. Add three more.

`GET /available_slots` returns a single `AvailableTimeSlots` object (verified: [src/api/schema.ts](../src/api/schema.ts) line 208) with one `slotsPerDay: TimeSlotsOfTheDay[]` field. The hook returns `data.slotsPerDay` directly so callers consume `TimeSlotsOfTheDay[]`.

```ts
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { getClientTimezone } from "../lib/timezone";
import type { components } from "./schema";

export function useEventTypes() { /* unchanged */ }

export function useEventType(eventTypeId: string | undefined) {
  return useQuery({
    queryKey: ["event_types"],
    queryFn: async () => {
      const { data, error } = await api.GET("/calendar/event_types");
      if (error) throw error;
      return data;
    },
    enabled: !!eventTypeId,
    select: (list) => list?.find((et) => et.eventTypeId === eventTypeId),
  });
}

export function useAvailableSlots(eventTypeId: string | undefined) {
  const clientTimeZone = getClientTimezone();
  return useQuery({
    enabled: !!eventTypeId,
    queryKey: ["available_slots", eventTypeId, clientTimeZone],
    queryFn: async () => {
      const { data, error } = await api.GET(
        "/calendar/event_types/{eventTypeId}/available_slots",
        { params: { path: { eventTypeId: eventTypeId! }, query: { clientTimeZone } } },
      );
      if (error) throw error;
      return data?.slotsPerDay ?? [];
    },
  });
}

export type CreateScheduledEventBody = components["schemas"]["CreateScheduledEvent"];

export function useCreateScheduledEvent() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CreateScheduledEventBody) => {
      const { data, error } = await api.POST("/calendar/scheduled_events", { body });
      if (error) throw error;
      return data!;
    },
    onSuccess: (_d, vars) => {
      qc.invalidateQueries({ queryKey: ["scheduled_events"] });
      qc.invalidateQueries({ queryKey: ["available_slots", vars.eventTypeId] });
    },
  });
}
```

`useEventType` reuses the `["event_types"]` cache key — navigating from `/` triggers no extra network call (the `select` projection runs against cached data).

## 4. SlotPicker component

File: `src/components/SlotPicker.tsx`. Two-pane: Mantine `DatePicker` left (window-clamped, days-without-slots disabled), vertical `Button` list right. Slot identity bubbles up as `{ date, time, duration }`.

```ts
import { DatePicker } from "@mantine/dates";
import type { components } from "../api/schema";
import { getBookingWindow } from "../lib/bookingWindow";
import { dayjs } from "../lib/dayjs";

export type SelectedSlot = { date: string; time: string; duration: number };

type Props = {
  slotsPerDay: components["schemas"]["TimeSlotsOfTheDay"][];
  onSelect: (slot: SelectedSlot) => void;
};
```

Inside:

```tsx
const { min, max } = getBookingWindow();
const slotMap = new Map(slotsPerDay.map((d) => [d.date, d.timeSlots]));

<DatePicker
  value={selectedDate}
  onChange={setSelectedDate}
  minDate={min}
  maxDate={max}
  excludeDate={(d) => {
    const iso = dayjs(d).format("YYYY-MM-DD");
    const ts = slotMap.get(iso);
    return !ts || ts.length === 0;
  }}
/>
```

Right pane: when a date is selected, `slotMap.get(iso)?.map((t) => <Button onClick={() => onSelect({ date: iso, time: t.timeStart, duration: t.duration })}>{t.timeStart}</Button>)`. Wrap in `Stack` + `ScrollArea` for long lists. Empty state when no day chosen yet: `<Text c="dimmed">Pick a day to see times</Text>`.

The component owns only `selectedDate`. The parent owns submission. Mantine v9 `DatePicker` `onChange` yields `string | null` — pick one shape and stick with it.

## 5. BookEventPage — fetch, render, modal-on-click

File: `src/pages/guest/BookEventPage.tsx`. Reads `:eventTypeId` from `useParams`. Calls `useEventType(id)` + `useAvailableSlots(id)`. Renders header (name, duration badge, description) above `<SlotPicker>`. Modal opens on slot click.

```tsx
const form = useForm({
  initialValues: { guestName: "", guestEmail: "" },
  validate: {
    guestName: (v) => (v.trim().length < 2 ? "Required" : null),
    guestEmail: (v) => (/^\S+@\S+\.\S+$/.test(v) ? null : "Invalid email"),
  },
});

const create = useCreateScheduledEvent();

const onSubmit = form.onSubmit((values) => {
  if (!selectedSlot || !id) return;
  create.mutate(
    {
      eventTypeId: id,
      date: selectedSlot.date,
      time: selectedSlot.time,
      guestTimezone: getClientTimezone(),
      ...values,
    },
    {
      onSuccess: (scheduled) => {
        notifications.show({ color: "green", title: "Booked", message: "See you then!" });
        navigate(`/book/${id}/confirmed`, { state: { eventType, ...selectedSlot, scheduled } });
      },
      onError: (e) =>
        notifications.show({ color: "red", title: "Couldn't book", message: String(e) }),
    },
  );
});
```

Loading/error states match [src/pages/guest/EventTypesPage.tsx](../src/pages/guest/EventTypesPage.tsx): `<Center><Loader/></Center>` and `<Alert color="red">`. Use `Modal` from `@mantine/core` (not `@mantine/modals`).

## 6. BookingConfirmedPage — read state, fall back gracefully

File: `src/pages/guest/BookingConfirmedPage.tsx`. Read details from `useLocation().state` (typed). On hard refresh `state` is `null` — render a minimal "Booking confirmed" message with a "Back to event types" button, no error.

```tsx
type Nav = { eventType?: EventType; date?: string; time?: string };
const { state } = useLocation() as { state: Nav | null };

return (
  <Stack align="center" gap="md" mt="xl">
    <Title order={2}>You're booked!</Title>
    {state?.eventType ? (
      <Text>
        {state.eventType.eventTypeName} on {state.date} at {state.time}{" "}
        ({state.eventType.durationMinutes} min)
      </Text>
    ) : (
      <Text c="dimmed">Your booking was created.</Text>
    )}
    <Button component={Link} to="/">Back to event types</Button>
  </Stack>
);
```

The toast fires from `BookEventPage`'s `onSuccess` (transient confirmation); the page is the durable artifact.

## 7. Wire the new routes

Edit `src/routes/index.tsx`:

```tsx
<Routes>
  <Route path="/" element={<EventTypesPage />} />
  <Route path="/book/:eventTypeId" element={<BookEventPage />} />
  <Route path="/book/:eventTypeId/confirmed" element={<BookingConfirmedPage />} />
</Routes>
```

[src/components/EventTypeCard.tsx](../src/components/EventTypeCard.tsx) line 20 already links to `/book/:eventTypeId` — no card changes.

## 8. Smoke-test the type plumbing

`npx tsc -b` (or rely on Vite's overlay). Watch for:

- `openapi-fetch` v0.17 expects path params under `params.path` and query under `params.query`.
- `@mantine/dates` v9 `DatePicker` `onChange` is `string | null` in v9 — pick one shape and stick with it.
- All deps used (`@mantine/form`, `@mantine/notifications`, `dayjs`, `dayjs/plugin/utc`, `dayjs/plugin/timezone`) are already in [package.json](../package.json) from STEP 1 — no installs needed.

## 9. Verification — runnable demo

```bash
cd frontend && npm run dev
```

At `http://localhost:5173/`:

1. Event types list renders (regression check from STEP 1).
2. Click any "Book" → lands on `/book/:id` with the event-type header + a `DatePicker` clamped to the next 14 days. Prism `--dynamic` returns random slots, so some days will be disabled — refresh until at least one day is enabled if needed.
3. Click an enabled day → time buttons appear on the right.
4. Click a time → modal opens; type a name + email, submit.
5. Modal closes, green toast fires, browser navigates to `/book/:id/confirmed` showing event type + date/time.
6. Hard-refresh the confirmation page → fallback "Your booking was created." renders without crashing.
7. Hit Back, pick a different event type — confirm in DevTools' Network panel that `event_types` is **not** refetched (cache-key reuse via `useEventType`'s `select`).
