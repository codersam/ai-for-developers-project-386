package com.hexlet.calendar.service;

import com.hexlet.calendar.domain.model.EventTypeEntity;
import com.hexlet.calendar.domain.model.ScheduledEventEntity;
import com.hexlet.calendar.domain.repo.EventTypeRepository;
import com.hexlet.calendar.domain.repo.ScheduledEventRepository;
import com.hexlet.calendar.generated.model.CreateScheduledEvent;
import com.hexlet.calendar.generated.model.ScheduledEvent;
import com.hexlet.calendar.generated.model.TimeSlot;
import com.hexlet.calendar.mapping.ScheduledEventMapper;
import com.hexlet.calendar.web.error.BadRequestException;
import com.hexlet.calendar.web.error.ConflictException;
import com.hexlet.calendar.web.error.NotFoundException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
public class ScheduledEventService {

    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ScheduledEventRepository scheduledEventRepo;
    private final EventTypeRepository eventTypeRepo;
    private final AvailabilityService availabilityService;
    private final ScheduledEventMapper mapper;
    private final IdGenerator idGen;
    private final Clock clock;

    public ScheduledEventService(
            ScheduledEventRepository scheduledEventRepo,
            EventTypeRepository eventTypeRepo,
            AvailabilityService availabilityService,
            ScheduledEventMapper mapper,
            IdGenerator idGen,
            Clock clock
    ) {
        this.scheduledEventRepo = scheduledEventRepo;
        this.eventTypeRepo = eventTypeRepo;
        this.availabilityService = availabilityService;
        this.mapper = mapper;
        this.idGen = idGen;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ScheduledEvent> list() {
        return mapper.toDtoList(scheduledEventRepo.findAll(Sort.by("utcStart")));
    }

    @Transactional(readOnly = true)
    public ScheduledEvent getById(String id) {
        return scheduledEventRepo.findById(id)
                .map(mapper::toDto)
                .orElseThrow(() -> new NotFoundException("Scheduled event not found: " + id));
    }

    @Transactional
    public ScheduledEvent create(CreateScheduledEvent req) {
        EventTypeEntity eventType = eventTypeRepo.findById(req.getEventTypeId())
                .orElseThrow(() -> new NotFoundException("Event type not found: " + req.getEventTypeId()));

        ZoneId guestZone;
        try {
            guestZone = ZoneId.of(req.getGuestTimezone());
        } catch (DateTimeException ex) {
            throw new BadRequestException("Invalid guestTimezone: " + req.getGuestTimezone());
        }

        LocalTime time;
        try {
            time = LocalTime.parse(req.getTime());
        } catch (DateTimeParseException ex) {
            throw new BadRequestException("Invalid time: " + req.getTime());
        }

        Instant utcStart = LocalDateTime.of(req.getDate(), time).atZone(guestZone).toInstant();
        Instant utcEnd = utcStart.plus(Duration.ofMinutes(eventType.getDurationMinutes()));

        if (utcStart.isBefore(Instant.now(clock))) {
            throw new BadRequestException("Cannot book a slot in the past");
        }

        LocalDate today = LocalDate.now(clock.withZone(guestZone));
        if (req.getDate().isBefore(today) || req.getDate().isAfter(today.plusDays(13))) {
            throw new BadRequestException("Booking date is outside the 14-day window");
        }

        if (scheduledEventRepo.existsOverlapping(utcStart, utcEnd)) {
            throw new ConflictException("Slot is no longer available");
        }

        String requestedTime = HHMMSS.format(time);
        boolean aligned = availabilityService
                .listAvailableSlots(req.getEventTypeId(), req.getGuestTimezone()).stream()
                .filter(d -> d.getDate().equals(req.getDate()))
                .flatMap(d -> d.getTimeSlots().stream())
                .map(TimeSlot::getTimeStart)
                .anyMatch(requestedTime::equals);
        if (!aligned) {
            // Disambiguate: a recently-committed booking from another transaction may have
            // landed between our existsOverlapping check above and listAvailableSlots, which
            // recomputes a fresh DB read. If so, this is a race outcome → 409, not 400.
            if (scheduledEventRepo.existsOverlapping(utcStart, utcEnd)) {
                throw new ConflictException("Slot is no longer available");
            }
            throw new BadRequestException("Slot is not available");
        }

        ScheduledEventEntity entity = new ScheduledEventEntity();
        entity.setId(idGen.forScheduledEvent());
        entity.setEventTypeId(eventType.getId());
        entity.setUtcStart(utcStart.atOffset(ZoneOffset.UTC));
        entity.setUtcEnd(utcEnd.atOffset(ZoneOffset.UTC));
        entity.setDurationMinutes(eventType.getDurationMinutes());
        entity.setSubject(req.getSubject());
        entity.setNotes(req.getNotes());
        entity.setGuestName(req.getGuestName());
        entity.setGuestEmail(req.getGuestEmail());
        entity.setGuestTimezone(req.getGuestTimezone());

        try {
            return mapper.toDto(scheduledEventRepo.saveAndFlush(entity));
        } catch (DataIntegrityViolationException ex) {
            if (isExclusionViolation(ex.getMostSpecificCause())) {
                throw new ConflictException("Slot is no longer available");
            }
            throw ex;
        } catch (CannotAcquireLockException ex) {
            // Postgres deadlock during EXCLUDE constraint check (SQLSTATE 40P01) under
            // concurrent INSERTs targeting the same time range. The DB still settles to one
            // row; for the loser, this is semantically a slot collision, not a 5xx.
            throw new ConflictException("Slot is no longer available");
        }
    }

    private static boolean isExclusionViolation(Throwable cause) {
        return cause instanceof SQLException sql && "23P01".equals(sql.getSQLState());
    }
}
