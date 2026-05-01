package com.hexlet.calendar.domain.repo;

import com.hexlet.calendar.domain.model.CalendarConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarConfigRepository extends JpaRepository<CalendarConfigEntity, Short> {
}
