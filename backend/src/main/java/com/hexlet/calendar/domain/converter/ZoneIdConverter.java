package com.hexlet.calendar.domain.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.ZoneId;

@Converter(autoApply = false)
public class ZoneIdConverter implements AttributeConverter<ZoneId, String> {

    @Override
    public String convertToDatabaseColumn(ZoneId attribute) {
        return attribute == null ? null : attribute.getId();
    }

    @Override
    public ZoneId convertToEntityAttribute(String dbData) {
        return dbData == null ? null : ZoneId.of(dbData);
    }
}
