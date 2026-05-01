package com.hexlet.calendar.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

@Entity
@Table(name = "scheduled_events")
public class ScheduledEventEntity {

    @Id
    private String id;

    @Column(name = "event_type_id")
    private String eventTypeId;

    @Column(name = "utc_start")
    private OffsetDateTime utcStart;

    @Column(name = "utc_end")
    private OffsetDateTime utcEnd;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "subject")
    private String subject;

    @Column(name = "notes")
    private String notes;

    @Column(name = "guest_name")
    private String guestName;

    @Column(name = "guest_email")
    private String guestEmail;

    @Column(name = "guest_timezone")
    private String guestTimezone;

    @Column(name = "created_at", insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private OffsetDateTime createdAt;

    public ScheduledEventEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEventTypeId() {
        return eventTypeId;
    }

    public void setEventTypeId(String eventTypeId) {
        this.eventTypeId = eventTypeId;
    }

    public OffsetDateTime getUtcStart() {
        return utcStart;
    }

    public void setUtcStart(OffsetDateTime utcStart) {
        this.utcStart = utcStart;
    }

    public OffsetDateTime getUtcEnd() {
        return utcEnd;
    }

    public void setUtcEnd(OffsetDateTime utcEnd) {
        this.utcEnd = utcEnd;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getGuestName() {
        return guestName;
    }

    public void setGuestName(String guestName) {
        this.guestName = guestName;
    }

    public String getGuestEmail() {
        return guestEmail;
    }

    public void setGuestEmail(String guestEmail) {
        this.guestEmail = guestEmail;
    }

    public String getGuestTimezone() {
        return guestTimezone;
    }

    public void setGuestTimezone(String guestTimezone) {
        this.guestTimezone = guestTimezone;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
