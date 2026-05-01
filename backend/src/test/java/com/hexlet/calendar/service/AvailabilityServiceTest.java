package com.hexlet.calendar.service;

import com.hexlet.calendar.domain.model.CalendarConfigEntity;
import com.hexlet.calendar.domain.model.EventTypeEntity;
import com.hexlet.calendar.domain.repo.CalendarConfigRepository;
import com.hexlet.calendar.domain.repo.EventTypeRepository;
import com.hexlet.calendar.domain.repo.ScheduledEventRepository;
import com.hexlet.calendar.generated.model.TimeSlotsOfTheDay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AvailabilityServiceTest {

    private static final String EVENT_TYPE_ID = "et_intro_aaaaaa";

    private EventTypeRepository eventTypeRepo;
    private CalendarConfigRepository calendarConfigRepo;
    private ScheduledEventRepository scheduledEventRepo;
    private AvailabilityService service;

    @BeforeEach
    void setUp() {
        eventTypeRepo = mock(EventTypeRepository.class);
        calendarConfigRepo = mock(CalendarConfigRepository.class);
        scheduledEventRepo = mock(ScheduledEventRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-05-04T05:00:00Z"), ZoneOffset.UTC);
        service = new AvailabilityService(eventTypeRepo, calendarConfigRepo, scheduledEventRepo, clock);
    }

    @Test
    void happyPath_returnsExpectedShape() {
        EventTypeEntity et = new EventTypeEntity();
        et.setId(EVENT_TYPE_ID);
        et.setName("Intro");
        et.setDescription("d");
        et.setDurationMinutes(30);

        CalendarConfigEntity config = new CalendarConfigEntity();
        config.setId((short) 1);
        config.setOwnerName("Alex");
        config.setOwnerEmail("a@b.c");
        config.setOwnerTimezone(ZoneId.of("Europe/Berlin"));
        config.setStartOfDay(LocalTime.of(9, 0));
        config.setEndOfDay(LocalTime.of(17, 0));
        config.setWorkingDays(new Short[]{1, 2, 3, 4, 5});
        config.setBreaks(List.of());

        when(eventTypeRepo.findById(EVENT_TYPE_ID)).thenReturn(Optional.of(et));
        when(calendarConfigRepo.findById((short) 1)).thenReturn(Optional.of(config));
        when(scheduledEventRepo.findOverlapping(any(), any())).thenReturn(List.of());

        List<TimeSlotsOfTheDay> result = service.listAvailableSlots(EVENT_TYPE_ID, "Europe/Berlin");

        assertThat(result).hasSize(10);
        assertThat(result.get(0).getTimeSlots().get(0).getTimeStart()).isEqualTo("09:00:00");
        assertThat(result.get(0).getTimeSlots().get(0).getDuration()).isEqualTo(30);

        Instant expectedStart = Instant.parse("2026-05-03T22:00:00Z");
        Instant expectedEnd = Instant.parse("2026-05-17T22:00:00Z");
        verify(scheduledEventRepo).findOverlapping(eq(expectedStart), eq(expectedEnd));
    }

    @Test
    void unknownEventType_throws404() {
        when(eventTypeRepo.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listAvailableSlots("missing", "Europe/Berlin"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void invalidClientTimeZone_throws400() {
        assertThatThrownBy(() -> service.listAvailableSlots(EVENT_TYPE_ID, "Mars/Phobos"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(eventTypeRepo, never()).findById(any());
        verify(calendarConfigRepo, never()).findById(any());
        verify(scheduledEventRepo, never()).findOverlapping(any(), any());
    }
}
