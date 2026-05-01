package com.hexlet.calendar.domain.repo;

import com.hexlet.calendar.domain.model.EventTypeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventTypeRepository extends JpaRepository<EventTypeEntity, String> {
}
