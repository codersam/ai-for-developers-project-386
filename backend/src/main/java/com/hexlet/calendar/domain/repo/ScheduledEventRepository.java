package com.hexlet.calendar.domain.repo;

import com.hexlet.calendar.domain.model.ScheduledEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledEventRepository extends JpaRepository<ScheduledEventEntity, String> {
}
