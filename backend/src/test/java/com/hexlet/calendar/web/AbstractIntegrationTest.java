package com.hexlet.calendar.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hexlet.calendar.domain.repo.EventTypeRepository;
import com.hexlet.calendar.domain.repo.ScheduledEventRepository;
import com.hexlet.calendar.service.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockTestConfig.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected EventTypeRepository eventTypeRepo;

    @Autowired
    protected ScheduledEventRepository scheduledEventRepo;

    @Autowired
    protected IdGenerator idGen;
}
