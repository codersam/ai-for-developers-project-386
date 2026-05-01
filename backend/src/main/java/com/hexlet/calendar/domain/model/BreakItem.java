package com.hexlet.calendar.domain.model;

import java.time.LocalTime;

public class BreakItem {

    private LocalTime timeStart;
    private Integer duration;

    public BreakItem() {
    }

    public BreakItem(LocalTime timeStart, Integer duration) {
        this.timeStart = timeStart;
        this.duration = duration;
    }

    public LocalTime getTimeStart() {
        return timeStart;
    }

    public void setTimeStart(LocalTime timeStart) {
        this.timeStart = timeStart;
    }

    public Integer getDuration() {
        return duration;
    }

    public void setDuration(Integer duration) {
        this.duration = duration;
    }
}
