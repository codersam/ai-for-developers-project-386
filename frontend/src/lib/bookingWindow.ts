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
