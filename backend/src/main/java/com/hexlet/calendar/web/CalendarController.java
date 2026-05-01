package com.hexlet.calendar.web;

import com.hexlet.calendar.generated.api.CalendarApi;
import com.hexlet.calendar.generated.model.CreateEventType;
import com.hexlet.calendar.generated.model.CreateScheduledEvent;
import com.hexlet.calendar.generated.model.EventType;
import com.hexlet.calendar.generated.model.ScheduledEvent;
import com.hexlet.calendar.generated.model.TimeSlotsOfTheDay;
import com.hexlet.calendar.service.AvailabilityService;
import com.hexlet.calendar.service.EventTypeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CalendarController implements CalendarApi {

    private final EventTypeService eventTypeService;
    private final AvailabilityService availabilityService;

    public CalendarController(EventTypeService eventTypeService, AvailabilityService availabilityService) {
        this.eventTypeService = eventTypeService;
        this.availabilityService = availabilityService;
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
        return notImplemented();
    }

    @Override
    public ResponseEntity<ScheduledEvent> calendarServiceGetScheduledEventById(String scheduledEventId) {
        return notImplemented();
    }

    @Override
    public ResponseEntity<List<ScheduledEvent>> calendarServiceListScheduledEvents() {
        return notImplemented();
    }

    @Override
    public ResponseEntity<List<TimeSlotsOfTheDay>> calendarServiceListAvailableSlots(String eventTypeId, String clientTimeZone) {
        return ResponseEntity.ok(availabilityService.listAvailableSlots(eventTypeId, clientTimeZone));
    }

    private static <T> ResponseEntity<T> notImplemented() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
