package com.hexlet.calendar.mapping;

import com.hexlet.calendar.domain.model.ScheduledEventEntity;
import com.hexlet.calendar.generated.model.ScheduledEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ScheduledEventMapper {

    @Mapping(source = "id", target = "scheduledEventId")
    @Mapping(source = "utcStart", target = "utcDateStart")
    ScheduledEvent toDto(ScheduledEventEntity entity);

    List<ScheduledEvent> toDtoList(List<ScheduledEventEntity> entities);
}
