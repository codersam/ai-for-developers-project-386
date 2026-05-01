package com.hexlet.calendar.web;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

@TestConfiguration
public class FixedClockTestConfig {

    public static final Instant FIXED_NOW = Instant.parse("2026-05-04T05:00:00Z");

    @Bean
    @Primary
    public Clock clock() {
        return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    }
}
