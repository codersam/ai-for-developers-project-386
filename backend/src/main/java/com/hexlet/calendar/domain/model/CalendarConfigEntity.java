package com.hexlet.calendar.domain.model;

import com.hexlet.calendar.domain.converter.ZoneIdConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

@Entity
@Table(name = "calendar_config")
public class CalendarConfigEntity {

    @Id
    private Short id;

    @Column(name = "owner_name")
    private String ownerName;

    @Column(name = "owner_email")
    private String ownerEmail;

    @Convert(converter = ZoneIdConverter.class)
    @Column(name = "owner_timezone")
    private ZoneId ownerTimezone;

    @Column(name = "start_of_day")
    private LocalTime startOfDay;

    @Column(name = "end_of_day")
    private LocalTime endOfDay;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "working_days", columnDefinition = "smallint[]")
    private Short[] workingDays;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "breaks", columnDefinition = "jsonb")
    private List<BreakItem> breaks;

    public CalendarConfigEntity() {
    }

    public Short getId() {
        return id;
    }

    public void setId(Short id) {
        this.id = id;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail) {
        this.ownerEmail = ownerEmail;
    }

    public ZoneId getOwnerTimezone() {
        return ownerTimezone;
    }

    public void setOwnerTimezone(ZoneId ownerTimezone) {
        this.ownerTimezone = ownerTimezone;
    }

    public LocalTime getStartOfDay() {
        return startOfDay;
    }

    public void setStartOfDay(LocalTime startOfDay) {
        this.startOfDay = startOfDay;
    }

    public LocalTime getEndOfDay() {
        return endOfDay;
    }

    public void setEndOfDay(LocalTime endOfDay) {
        this.endOfDay = endOfDay;
    }

    public Short[] getWorkingDays() {
        return workingDays;
    }

    public void setWorkingDays(Short[] workingDays) {
        this.workingDays = workingDays;
    }

    public List<BreakItem> getBreaks() {
        return breaks;
    }

    public void setBreaks(List<BreakItem> breaks) {
        this.breaks = breaks;
    }
}
