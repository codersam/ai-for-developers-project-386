package com.hexlet.calendar.service;

import com.hexlet.calendar.domain.model.BreakItem;
import com.hexlet.calendar.domain.model.CalendarConfigEntity;
import com.hexlet.calendar.domain.model.EventTypeEntity;
import com.hexlet.calendar.domain.repo.CalendarConfigRepository;
import com.hexlet.calendar.domain.repo.EventTypeRepository;
import com.hexlet.calendar.domain.repo.ScheduledEventRepository;
import com.hexlet.calendar.generated.model.TimeSlot;
import com.hexlet.calendar.generated.model.TimeSlotsOfTheDay;
import com.hexlet.calendar.time.SlotMath;
import com.hexlet.calendar.time.SlotMath.BreakWindow;
import com.hexlet.calendar.time.SlotMath.UtcRange;
import com.hexlet.calendar.web.error.BadRequestException;
import com.hexlet.calendar.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class AvailabilityService {

    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final EventTypeRepository eventTypeRepo;
    private final CalendarConfigRepository calendarConfigRepo;
    private final ScheduledEventRepository scheduledEventRepo;
    private final Clock clock;

    public AvailabilityService(
            EventTypeRepository eventTypeRepo,
            CalendarConfigRepository calendarConfigRepo,
            ScheduledEventRepository scheduledEventRepo,
            Clock clock
    ) {
        this.eventTypeRepo = eventTypeRepo;
        this.calendarConfigRepo = calendarConfigRepo;
        this.scheduledEventRepo = scheduledEventRepo;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TimeSlotsOfTheDay> listAvailableSlots(String eventTypeId, String clientTimeZoneRaw) {
        ZoneId clientZone;
        try {
            clientZone = ZoneId.of(clientTimeZoneRaw);
        } catch (DateTimeException ex) {
            throw new BadRequestException("Invalid timezone: " + clientTimeZoneRaw);
        }

        EventTypeEntity eventType = eventTypeRepo.findById(eventTypeId)
                .orElseThrow(() -> new NotFoundException("Event type not found: " + eventTypeId));

        CalendarConfigEntity config = calendarConfigRepo.findById((short) 1)
                .orElseThrow(() -> new IllegalStateException("Owner calendar not configured"));

        LocalDate clientToday = LocalDate.now(clock.withZone(clientZone));
        Instant windowStart = clientToday.atStartOfDay(clientZone).toInstant();
        Instant windowEnd = clientToday.plusDays(14).atStartOfDay(clientZone).toInstant();

        Set<DayOfWeek> workingDays = Arrays.stream(config.getWorkingDays())
                .map(s -> DayOfWeek.of(s.intValue()))
                .collect(Collectors.toUnmodifiableSet());

        List<BreakItem> rawBreaks = config.getBreaks() == null ? List.of() : config.getBreaks();
        List<BreakWindow> breaks = rawBreaks.stream()
                .map(bi -> new BreakWindow(bi.getTimeStart(),
                        bi.getTimeStart().plusMinutes(bi.getDuration())))
                .toList();

        List<UtcRange> existingBookings = scheduledEventRepo
                .findOverlapping(windowStart, windowEnd).stream()
                .map(se -> new UtcRange(se.getUtcStart().toInstant(),
                        se.getUtcEnd().toInstant()))
                .toList();

        List<Instant> slots = SlotMath.generate(
                eventType.getDurationMinutes(),
                config.getOwnerTimezone(),
                config.getStartOfDay(),
                config.getEndOfDay(),
                workingDays,
                breaks,
                existingBookings,
                clientZone,
                clock
        );

        Integer dur = eventType.getDurationMinutes();
        return slots.stream()
                .collect(Collectors.groupingBy(
                        i -> i.atZone(clientZone).toLocalDate(),
                        TreeMap::new,
                        Collectors.toList()))
                .entrySet().stream()
                .map(e -> new TimeSlotsOfTheDay(
                        e.getKey(),
                        e.getValue().stream().sorted()
                                .map(i -> new TimeSlot(HHMMSS.format(i.atZone(clientZone).toLocalTime()), dur))
                                .toList()))
                .toList();
    }
}
