package com.hexlet.calendar.domain.repo;

import com.hexlet.calendar.domain.model.ScheduledEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ScheduledEventRepository extends JpaRepository<ScheduledEventEntity, String> {

    @Query(value = """
        SELECT * FROM scheduled_events
        WHERE tstzrange(utc_start, utc_end, '[)') && tstzrange(:windowStart, :windowEnd, '[)')
        """, nativeQuery = true)
    List<ScheduledEventEntity> findOverlapping(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd
    );
}
