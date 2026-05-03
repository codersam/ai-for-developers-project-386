package com.hexlet.calendar.service;

import com.hexlet.calendar.domain.model.EventTypeEntity;
import com.hexlet.calendar.domain.repo.EventTypeRepository;
import com.hexlet.calendar.generated.model.CreateEventType;
import com.hexlet.calendar.generated.model.EventType;
import com.hexlet.calendar.mapping.EventTypeMapper;
import com.hexlet.calendar.web.error.BadRequestException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EventTypeService {

    private final EventTypeRepository repo;
    private final EventTypeMapper mapper;
    private final IdGenerator idGen;

    public EventTypeService(EventTypeRepository repo, EventTypeMapper mapper, IdGenerator idGen) {
        this.repo = repo;
        this.mapper = mapper;
        this.idGen = idGen;
    }

    @Transactional(readOnly = true)
    public List<EventType> list() {
        return mapper.toDtoList(repo.findAll(Sort.by("createdAt")));
    }

    @Transactional
    public EventType create(CreateEventType req) {
        if (req.getDurationMinutes() == null || req.getDurationMinutes() < 1) {
            throw new BadRequestException("durationMinutes: must be greater than or equal to 1");
        }
        EventTypeEntity entity = mapper.toEntity(req);
        entity.setId(idGen.forEventType(req.getEventTypeName()));
        return mapper.toDto(repo.save(entity));
    }
}
