package com.hexlet.calendar.time;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class SlotMath {

    private SlotMath() {
    }

    public record BreakWindow(LocalTime start, LocalTime endExclusive) {
    }

    public record UtcRange(Instant start, Instant endExclusive) {
    }

    public static List<Instant> generate(
            int durationMinutes,
            ZoneId ownerZone,
            LocalTime startOfDay,
            LocalTime endOfDay,
            Set<DayOfWeek> workingDays,
            List<BreakWindow> breaks,
            List<UtcRange> existingBookings,
            ZoneId clientZone,
            Clock clock
    ) {
        LocalDate clientToday = LocalDate.now(clock.withZone(clientZone));
        Instant windowStart = clientToday.atStartOfDay(clientZone).toInstant();
        Instant windowEnd = clientToday.plusDays(14).atStartOfDay(clientZone).toInstant();
        Instant nowUtc = Instant.now(clock);

        TreeSet<Instant> slots = new TreeSet<>();

        LocalDate sweepStart = clientToday.minusDays(1);
        LocalDate sweepEnd = clientToday.plusDays(15);

        for (LocalDate day = sweepStart; !day.isAfter(sweepEnd); day = day.plusDays(1)) {
            if (!workingDays.contains(day.getDayOfWeek())) {
                continue;
            }
            LocalTime t = startOfDay;
            while (true) {
                LocalTime slotEnd = t.plusMinutes(durationMinutes);
                if (slotEnd.isAfter(endOfDay)) {
                    break;
                }
                if (!overlapsBreak(t, slotEnd, breaks)) {
                    Instant slotInstant = ZonedDateTime.of(day, t, ownerZone).toInstant();
                    Instant slotInstantEnd = slotInstant.plusSeconds(durationMinutes * 60L);
                    if (!slotInstant.isBefore(windowStart)
                            && !slotInstantEnd.isAfter(windowEnd)
                            && !slotInstant.isBefore(nowUtc)
                            && !overlapsBooking(slotInstant, slotInstantEnd, existingBookings)) {
                        slots.add(slotInstant);
                    }
                }
                t = slotEnd;
            }
        }

        return List.copyOf(new ArrayList<>(slots));
    }

    private static boolean overlapsBreak(LocalTime slotStart, LocalTime slotEnd, List<BreakWindow> breaks) {
        for (BreakWindow b : breaks) {
            if (slotStart.isBefore(b.endExclusive()) && b.start().isBefore(slotEnd)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlapsBooking(Instant slotStart, Instant slotEnd, List<UtcRange> bookings) {
        for (UtcRange r : bookings) {
            if (slotStart.isBefore(r.endExclusive()) && r.start().isBefore(slotEnd)) {
                return true;
            }
        }
        return false;
    }
}
