package com.hexlet.calendar.web;

import com.hexlet.calendar.generated.api.CalendarApi;
import com.hexlet.calendar.generated.model.CreateEventType;
import com.hexlet.calendar.generated.model.CreateScheduledEvent;
import com.hexlet.calendar.generated.model.EventType;
import com.hexlet.calendar.generated.model.ScheduledEvent;
import com.hexlet.calendar.generated.model.TimeSlotsOfTheDay;
import com.hexlet.calendar.service.AvailabilityService;
import com.hexlet.calendar.service.EventTypeService;
import com.hexlet.calendar.service.ScheduledEventService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CalendarController implements CalendarApi {

    private final EventTypeService eventTypeService;
    private final AvailabilityService availabilityService;
    private final ScheduledEventService scheduledEventService;

    public CalendarController(
            EventTypeService eventTypeService,
            AvailabilityService availabilityService,
            ScheduledEventService scheduledEventService
    ) {
        this.eventTypeService = eventTypeService;
        this.availabilityService = availabilityService;
        this.scheduledEventService = scheduledEventService;
    }

    @Override
    public ResponseEntity<EventType> calendarServiceCreateEventType(CreateEventType createEventType) {
        return ResponseEntity.ok(eventTypeService.create(createEventType));
    }

    @Override
    public ResponseEntity<List<EventType>> calendarServiceListEventTypes() {
        return ResponseEntity.ok(eventTypeService.list());
    }

    @Override
    public ResponseEntity<ScheduledEvent> calendarServiceCreateScheduledEvent(CreateScheduledEvent createScheduledEvent) {
        return ResponseEntity.ok(scheduledEventService.create(createScheduledEvent));
    }

    @Override
    public ResponseEntity<ScheduledEvent> calendarServiceGetScheduledEventById(String scheduledEventId) {
        return ResponseEntity.ok(scheduledEventService.getById(scheduledEventId));
    }

    @Override
    public ResponseEntity<List<ScheduledEvent>> calendarServiceListScheduledEvents() {
        return ResponseEntity.ok(scheduledEventService.list());
    }

    @Override
    public ResponseEntity<List<TimeSlotsOfTheDay>> calendarServiceListAvailableSlots(String eventTypeId, String clientTimeZone) {
        return ResponseEntity.ok(availabilityService.listAvailableSlots(eventTypeId, clientTimeZone));
    }
}
