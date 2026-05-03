package com.hexlet.calendar.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.hexlet.calendar.generated.model.EventType;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
class EventTypeControllerIT extends AbstractIntegrationTest {

    @Test
    void happyPath_postReturns200WithGeneratedId() throws Exception {
        String body = """
                {"eventTypeName":"Intro Call","description":"Short chat","durationMinutes":30}""";

        mockMvc.perform(post("/api/calendar/event_types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventTypeId").value(matchesPattern("^et_intro-call_[a-z0-9]{6}$")))
                .andExpect(jsonPath("$.eventTypeName").value("Intro Call"))
                .andExpect(jsonPath("$.description").value("Short chat"))
                .andExpect(jsonPath("$.durationMinutes").value(30));
    }

    @Test
    void missingRequiredField_returns400ErrorBody() throws Exception {
        mockMvc.perform(post("/api/calendar/event_types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("eventTypeName")));
    }

    @Test
    void malformedJson_returns400ErrorBody() throws Exception {
        mockMvc.perform(post("/api/calendar/event_types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("Malformed")));
    }

    @Test
    void negativeDuration_returns400ErrorBody() throws Exception {
        String body = """
                {"eventTypeName":"Bad","description":"d","durationMinutes":-5}""";

        mockMvc.perform(post("/api/calendar/event_types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("durationMinutes")));
    }

    @Test
    void listEmpty_returns200EmptyArray() throws Exception {
        mockMvc.perform(get("/api/calendar/event_types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listAfterCreate_orderedByCreatedAtAscending() throws Exception {
        postEventType("First Type");
        Thread.sleep(5);
        postEventType("Second Type");
        Thread.sleep(5);
        postEventType("Third Type");

        MvcResult result = mockMvc.perform(get("/api/calendar/event_types"))
                .andExpect(status().isOk())
                .andReturn();

        List<EventType> body = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<List<EventType>>() {});

        assertThat(body).hasSize(3);
        assertThat(body.stream().map(EventType::getEventTypeName).toList())
                .containsExactly("First Type", "Second Type", "Third Type");
    }

    private void postEventType(String name) throws Exception {
        String body = """
                {"eventTypeName":"%s","description":"d","durationMinutes":30}""".formatted(name);
        mockMvc.perform(post("/api/calendar/event_types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
