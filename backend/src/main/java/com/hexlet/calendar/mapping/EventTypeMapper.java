package com.hexlet.calendar.mapping;

import com.hexlet.calendar.domain.model.EventTypeEntity;
import com.hexlet.calendar.generated.model.CreateEventType;
import com.hexlet.calendar.generated.model.EventType;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface EventTypeMapper {

    @Mapping(source = "id", target = "eventTypeId")
    @Mapping(source = "name", target = "eventTypeName")
    EventType toDto(EventTypeEntity entity);

    List<EventType> toDtoList(List<EventTypeEntity> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(source = "eventTypeName", target = "name")
    EventTypeEntity toEntity(CreateEventType dto);
}
