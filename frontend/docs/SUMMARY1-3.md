# Frontend Review — Alignment Summary (STEPS 1–3)

Reviewed the entire frontend tree against the plan, task brief, and OpenAPI spec. The project lines up very closely on all three axes.

## Alignment with [../CLAUDE.md](../CLAUDE.md) — ~95%

**Matches the plan exactly:**
- Stack: Vite + React 19 + TS + Mantine v9 + React Router v7 + TanStack Query v5 + `openapi-fetch` + dayjs + Prism — every dependency from the plan is in [../package.json](../package.json).
- Folder layout under [../src/](../src/) mirrors the plan's tree.
- Provider stack in [../src/main.tsx](../src/main.tsx) is the exact `MantineProvider → Notifications → QueryClientProvider → BrowserRouter` order, with all three Mantine CSS imports and `<ColorSchemeScript />`.
- AppShell with header role nav in [../src/App.tsx](../src/App.tsx).
- All five planned routes wired up in [../src/routes/index.tsx](../src/routes/index.tsx).
- Generated [schema.ts](../src/api/schema.ts), [client.ts](../src/api/client.ts) (`baseUrl: "/api"`), and the named hooks in [hooks.ts](../src/api/hooks.ts).
- Vite proxy `/api → 127.0.0.1:4010` in [../vite.config.ts](../vite.config.ts).
- 14-day window helper in [bookingWindow.ts:3](../src/lib/bookingWindow.ts#L3), TZ helper in [timezone.ts:1](../src/lib/timezone.ts#L1), dayjs UTC+timezone plugins in [dayjs.ts:5-6](../src/lib/dayjs.ts#L5-L6).
- Two-pane `DatePicker` + time list ([SlotPicker.tsx](../src/components/SlotPicker.tsx)) with `excludeDate` honoring availability and `minDate`/`maxDate` clamping the window.

**Small deviations from the plan (not bugs):**
- The plan called for separate `EventTypeForm.tsx` and `ScheduledEventList.tsx` components. Both are inlined in the pages instead — fine for this size, but worth extracting if either gets reused.
- The plan's `mock` script had `--dynamic`; the actual script in [../package.json:9](../package.json#L9) drops that flag, so Prism returns the YAML's static `example` payloads instead of randomized ones. That's actually nicer for demoing — just note it diverges.
- Owner event-types page uses `Card`s ([EventTypesAdminPage.tsx:88](../src/pages/owner/EventTypesAdminPage.tsx#L88)), not the `Table` the plan described. Cosmetic choice.
- Plan says `PLAN.md`; the file is `CLAUDE.md`. No content gap.

## Alignment with [../spec/task.txt](../spec/task.txt) — ~98%

Every brief requirement is implemented:

| Requirement | Where |
|---|---|
| No auth, predefined owner | No login flow; owner is just two header links. |
| Owner creates event types (title, description, duration) | [EventTypesAdminPage.tsx:105-137](../src/pages/owner/EventTypesAdminPage.tsx#L105-L137) |
| Owner views upcoming bookings (single list, all types) | [ScheduledEventsPage.tsx](../src/pages/owner/ScheduledEventsPage.tsx), grouped by day with type lookup |
| Guest browses event types | [EventTypesPage.tsx](../src/pages/guest/EventTypesPage.tsx) |
| Guest picks type → opens calendar → picks free slot | [BookEventPage.tsx:106-167](../src/pages/guest/BookEventPage.tsx#L106-L167) + `SlotPicker` |
| Booking creation | [BookEventPage.tsx:82-104](../src/pages/guest/BookEventPage.tsx#L82-L104) |
| 14-day window | `BOOKING_WINDOW_DAYS = 14` in [bookingWindow.ts:3](../src/lib/bookingWindow.ts#L3); enforced via `getBookingWindow()` (DatePicker bounds), `excludeDate` (no-slots days), and a defensive `slotsInWindow` filter at [BookEventPage.tsx:50-53](../src/pages/guest/BookEventPage.tsx#L50-L53) |
| No-double-booking rule | Correctly delegated to server; 4xx surfaces via Mantine notification at [BookEventPage.tsx:100-101](../src/pages/guest/BookEventPage.tsx#L100-L101) |

One note: task.txt literally says owner "specifies the ID" for an event type. The OpenAPI contract makes `eventTypeId` `readOnly` (server-generated), and the form correctly omits it. Implementation follows the spec, which is the right call — flag it back to whoever wrote the brief.

## Alignment with [../../typespec/main.tsp](../../typespec/main.tsp) / [../spec/openapi.yaml](../spec/openapi.yaml) — ~95%

All five operations are wired:
- `listEventTypes` → [hooks.ts:6](../src/api/hooks.ts#L6)
- `createEventType` → [hooks.ts:65](../src/api/hooks.ts#L65)
- `listAvailableSlots` (with `clientTimeZone` query) → [hooks.ts:30](../src/api/hooks.ts#L30)
- `createScheduledEvent` → [hooks.ts:48](../src/api/hooks.ts#L48)
- `listScheduledEvents` (with `clientTimeZone` query) → [hooks.ts:79](../src/api/hooks.ts#L79)

`CreateScheduledEvent` is fully populated — `eventTypeId`, `date`, `time`, `subject`, `notes`, `guestName`, `guestEmail`, `guestTimezone` all sent at [BookEventPage.tsx:85-91](../src/pages/guest/BookEventPage.tsx#L85-L91). `CreateEventType` form covers all three required fields. The `Calendar` model is in the schema but not yet used by any endpoint or page — that's consistent with the spec, which doesn't expose any `Calendar` operations.

## Real issues worth fixing

1. **Errors stringify to `[object Object]`.** `openapi-fetch` rejects with the schema's `Error` object (`{code, message}`), but every page renders it via `String(error)` ([EventTypesPage.tsx:17](../src/pages/guest/EventTypesPage.tsx#L17), [BookEventPage.tsx:67](../src/pages/guest/BookEventPage.tsx#L67) and `:101`, [EventTypesAdminPage.tsx:55](../src/pages/owner/EventTypesAdminPage.tsx#L55), [ScheduledEventsPage.tsx:54](../src/pages/owner/ScheduledEventsPage.tsx#L54)). User-visible text will be useless. Throw a real `Error(error.message)` in each hook (or render `error.message` directly).

2. **`useEventType` reuses the list query key without the id.** [hooks.ts:19](../src/api/hooks.ts#L19) uses `["event_types"]` and re-runs the same fetch as `useEventTypes`, then `select`s by id. It works (TanStack dedupes), but it's accidentally clever — if you ever switch to per-id fetching it'll silently break the cache. Consider adding the id to the key or just calling `useEventTypes` and finding locally in the page.

3. **`BookingConfirmedPage` relies on router state.** [BookingConfirmedPage.tsx:12](../src/pages/guest/BookingConfirmedPage.tsx#L12) — a refresh wipes the confirmation details and shows the dimmed fallback. Fine for a demo, but if you want shareable confirmation URLs you'd need to read the `scheduledEventId` from a query param.

4. **Owner page doesn't display `guestTimezone`** in [ScheduledEventsPage.tsx:71-80](../src/pages/owner/ScheduledEventsPage.tsx#L71-L80). Minor UX gap if guest and owner are in different zones — the owner sees the time in their own TZ but no hint of where the guest is.

5. **Prism is stateless** — already called out in the plan. New event types and bookings won't appear in subsequent list calls. Not a defect, just keep it in mind during demo.

## Bottom line

The implementation is a faithful execution of the plan and the spec. The only material bug is the error-rendering one (#1); the rest are minor refinements.
