package com.hexlet.calendar.time;

import com.hexlet.calendar.time.SlotMath.BreakWindow;
import com.hexlet.calendar.time.SlotMath.UtcRange;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SlotMathTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZoneId HONOLULU = ZoneId.of("Pacific/Honolulu");
    private static final ZoneId AUCKLAND = ZoneId.of("Pacific/Auckland");
    private static final Set<DayOfWeek> MON_FRI = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
    private static final LocalTime NINE = LocalTime.of(9, 0);
    private static final LocalTime SEVENTEEN = LocalTime.of(17, 0);

    private static Clock fixedAt(String iso) {
        return Clock.fixed(Instant.parse(iso), ZoneOffset.UTC);
    }

    @Test
    void typicalMonFri30MinYields16SlotsPerWorkingDay() {
        List<Instant> slots = SlotMath.generate(
                30, BERLIN, NINE, SEVENTEEN, MON_FRI,
                List.of(), List.of(), BERLIN, fixedAt("2026-05-04T05:00:00Z"));

        assertThat(slots).hasSize(10 * 16);
        assertThat(slots.get(0)).isEqualTo(Instant.parse("2026-05-04T07:00:00Z"));
    }

    @Test
    void weekendsAreSkipped() {
        List<Instant> slots = SlotMath.generate(
                30, BERLIN, NINE, SEVENTEEN, MON_FRI,
                List.of(), List.of(), BERLIN, fixedAt("2026-05-04T05:00:00Z"));

        LocalDate sat = LocalDate.parse("2026-05-09");
        LocalDate sun = LocalDate.parse("2026-05-10");
        assertThat(slots)
                .noneMatch(i -> i.atZone(BERLIN).toLocalDate().equals(sat))
                .noneMatch(i -> i.atZone(BERLIN).toLocalDate().equals(sun));
    }

    @Test
    void break_30MinEvent() {
        List<BreakWindow> breaks = List.of(new BreakWindow(LocalTime.of(12, 0), LocalTime.of(13, 0)));

        List<Instant> slots = SlotMath.generate(
                30, BERLIN, NINE, SEVENTEEN, MON_FRI,
                breaks, List.of(), BERLIN, fixedAt("2026-05-04T05:00:00Z"));

        assertThat(slots).hasSize(10 * 14);

        Instant noonMon = ZonedDateTime.of(LocalDate.parse("2026-05-04"), LocalTime.of(12, 0), BERLIN).toInstant();
        Instant halfPastNoonMon = ZonedDateTime.of(LocalDate.parse("2026-05-04"), LocalTime.of(12, 30), BERLIN).toInstant();
        assertThat(slots).doesNotContain(noonMon, halfPastNoonMon);
    }

    @Test
    void break_60MinEvent_halfOpenBoundary() {
        List<BreakWindow> breaks = List.of(new BreakWindow(LocalTime.of(12, 0), LocalTime.of(13, 0)));

        List<Instant> slots = SlotMath.generate(
                60, BERLIN, NINE, SEVENTEEN, MON_FRI,
                breaks, List.of(), BERLIN, fixedAt("2026-05-04T05:00:00Z"));

        LocalDate mon = LocalDate.parse("2026-05-04");
        List<Instant> mondaySlots = slots.stream()
                .filter(i -> i.atZone(BERLIN).toLocalDate().equals(mon))
                .toList();

        List<Instant> expected = List.of(
                ZonedDateTime.of(mon, LocalTime.of(9, 0), BERLIN).toInstant(),
                ZonedDateTime.of(mon, LocalTime.of(10, 0), BERLIN).toInstant(),
                ZonedDateTime.of(mon, LocalTime.of(11, 0), BERLIN).toInstant(),
                ZonedDateTime.of(mon, LocalTime.of(13, 0), BERLIN).toInstant(),
                ZonedDateTime.of(mon, LocalTime.of(14, 0), BERLIN).toInstant(),
                ZonedDateTime.of(mon, LocalTime.of(15, 0), BERLIN).toInstant(),
                ZonedDateTime.of(mon, LocalTime.of(16, 0), BERLIN).toInstant()
        );
        assertThat(mondaySlots).containsExactlyElementsOf(expected);
    }

    @Test
    void existingBookingRemovesExactSlot() {
        UtcRange booking = new UtcRange(
                Instant.parse("2026-05-04T08:00:00Z"),
                Instant.parse("2026-05-04T08:30:00Z"));

        List<Instant> slots = SlotMath.generate(
                30, BERLIN, NINE, SEVENTEEN, MON_FRI,
                List.of(), List.of(booking), BERLIN, fixedAt("2026-05-04T05:00:00Z"));

        assertThat(slots).doesNotContain(Instant.parse("2026-05-04T08:00:00Z"));
        assertThat(slots).contains(
                Instant.parse("2026-05-04T07:30:00Z"),
                Instant.parse("2026-05-04T08:30:00Z"));
    }

    @Test
    void existing30MinBookingBlocks60MinEvent() {
        UtcRange booking = new UtcRange(
                Instant.parse("2026-05-04T08:00:00Z"),
                Instant.parse("2026-05-04T08:30:00Z"));

        List<Instant> slots = SlotMath.generate(
                60, BERLIN, NINE, SEVENTEEN, MON_FRI,
                List.of(), List.of(booking), BERLIN, fixedAt("2026-05-04T05:00:00Z"));

        assertThat(slots).doesNotContain(Instant.parse("2026-05-04T08:00:00Z"));
        assertThat(slots).contains(Instant.parse("2026-05-04T07:00:00Z"));
    }

    @Test
    void pastSlotsFiltered_today() {
        List<Instant> slots = SlotMath.generate(
                30, BERLIN, NINE, SEVENTEEN, MON_FRI,
                List.of(), List.of(), BERLIN, fixedAt("2026-05-04T08:30:00Z"));

        assertThat(slots).doesNotContain(
                Instant.parse("2026-05-04T07:00:00Z"),
                Instant.parse("2026-05-04T07:30:00Z"));
        assertThat(slots).contains(Instant.parse("2026-05-04T08:30:00Z"));
    }

    @Test
    void windowEndpoints_todayIncluded_today14Excluded() {
        Clock clock = fixedAt("2026-05-03T22:00:00Z");

        List<Instant> slots = SlotMath.generate(
                30, BERLIN, NINE, SEVENTEEN, MON_FRI,
                List.of(), List.of(), BERLIN, clock);

        LocalDate today = LocalDate.parse("2026-05-04");
        LocalDate today14 = today.plusDays(14);

        assertThat(slots).anyMatch(i -> i.atZone(BERLIN).toLocalDate().equals(today));
        assertThat(slots).noneMatch(i -> i.atZone(BERLIN).toLocalDate().equals(today14));
    }

    @Test
    void berlinTuesday09GroupsToHonoluluMonday21() {
        List<Instant> slots = SlotMath.generate(
                30, BERLIN, NINE, SEVENTEEN, MON_FRI,
                List.of(), List.of(), HONOLULU, fixedAt("2026-05-04T05:00:00Z"));

        Instant berlinTue09 = Instant.parse("2026-05-05T07:00:00Z");
        assertThat(slots).contains(berlinTue09);
        assertThat(berlinTue09.atZone(HONOLULU).toLocalDate()).isEqualTo(LocalDate.parse("2026-05-04"));
    }

    @Test
    void berlinFri16GroupsToAucklandSat02() {
        List<Instant> slots = SlotMath.generate(
                30, BERLIN, NINE, SEVENTEEN, MON_FRI,
                List.of(), List.of(), AUCKLAND, fixedAt("2026-05-04T05:00:00Z"));

        Instant berlinFri16 = Instant.parse("2026-05-08T14:00:00Z");
        assertThat(slots).contains(berlinFri16);
        assertThat(berlinFri16.atZone(AUCKLAND).toLocalDate()).isEqualTo(LocalDate.parse("2026-05-09"));
        assertThat(berlinFri16.atZone(AUCKLAND).toLocalTime()).isEqualTo(LocalTime.of(2, 0));
    }

    @Test
    void dstSpringForward_noDuplicateInstants() {
        Set<DayOfWeek> sunOnly = EnumSet.of(DayOfWeek.SUNDAY);
        Clock clock = fixedAt("2026-03-28T23:00:00Z");

        List<Instant> slots = SlotMath.generate(
                30, BERLIN, LocalTime.of(1, 30), LocalTime.of(4, 0), sunOnly,
                List.of(), List.of(), BERLIN, clock);

        assertThat(slots).doesNotHaveDuplicates();

        LocalDate dstDay = LocalDate.parse("2026-03-29");
        Instant from0200 = ZonedDateTime.of(dstDay, LocalTime.of(2, 0), BERLIN).toInstant();
        Instant from0300 = ZonedDateTime.of(dstDay, LocalTime.of(3, 0), BERLIN).toInstant();
        assertThat(from0200).isEqualTo(from0300);
    }

    @Test
    void dstFallBack_ambiguousPicksEarlierOffset() {
        Set<DayOfWeek> sunOnly = EnumSet.of(DayOfWeek.SUNDAY);
        Clock clock = fixedAt("2026-10-24T22:00:00Z");

        List<Instant> slots = SlotMath.generate(
                60, BERLIN, LocalTime.of(2, 0), LocalTime.of(3, 0), sunOnly,
                List.of(), List.of(), BERLIN, clock);

        assertThat(slots).contains(Instant.parse("2026-10-25T00:00:00Z"));
    }

    @Test
    void dstSpringForward_skipsMissingHourLocal() {
        Set<DayOfWeek> sunOnly = EnumSet.of(DayOfWeek.SUNDAY);
        Clock clock = fixedAt("2026-03-28T23:00:00Z");

        List<Instant> slots = SlotMath.generate(
                30, BERLIN, LocalTime.of(1, 30), LocalTime.of(4, 0), sunOnly,
                List.of(), List.of(), BERLIN, clock);

        LocalDate dstDay = LocalDate.parse("2026-03-29");
        for (Instant i : slots) {
            ZonedDateTime zdt = i.atZone(BERLIN);
            if (!zdt.toLocalDate().equals(dstDay)) {
                continue;
            }
            LocalTime lt = zdt.toLocalTime();
            assertThat(lt.isBefore(LocalTime.of(2, 0)) || !lt.isBefore(LocalTime.of(3, 0)))
                    .as("Slot %s falls inside the missing 02:00–03:00 hour", lt)
                    .isTrue();
        }
    }

    @Test
    void dstFallBack_instantsStrictlyMonotonic() {
        Set<DayOfWeek> sunOnly = EnumSet.of(DayOfWeek.SUNDAY);
        Clock clock = fixedAt("2026-10-24T22:00:00Z");

        List<Instant> slots = SlotMath.generate(
                30, BERLIN, LocalTime.of(2, 0), LocalTime.of(4, 0), sunOnly,
                List.of(), List.of(), BERLIN, clock);

        LocalDate dstDay = LocalDate.parse("2026-10-25");
        List<Instant> dstDaySlots = slots.stream()
                .filter(i -> i.atZone(BERLIN).toLocalDate().equals(dstDay))
                .toList();

        assertThat(dstDaySlots).hasSize(4).isSortedAccordingTo(Comparator.naturalOrder());
        for (int i = 1; i < dstDaySlots.size(); i++) {
            assertThat(dstDaySlots.get(i)).isAfter(dstDaySlots.get(i - 1));
        }
    }

    @Test
    void emptyResultWhenNoWorkingDays() {
        List<Instant> slots = SlotMath.generate(
                30, BERLIN, NINE, SEVENTEEN, EnumSet.noneOf(DayOfWeek.class),
                List.of(), List.of(), BERLIN, fixedAt("2026-05-04T05:00:00Z"));

        assertThat(slots).isEmpty();
    }
}
