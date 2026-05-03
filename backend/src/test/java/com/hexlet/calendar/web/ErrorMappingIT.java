package com.hexlet.calendar.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
class ErrorMappingIT extends AbstractIntegrationTest {

    @Test
    void malformedJson_returns400ErrorBody() throws Exception {
        mockMvc.perform(post("/api/calendar/event_types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Malformed")));
    }

    @Test
    void missingRequiredField_returns400ErrorBody() throws Exception {
        mockMvc.perform(post("/api/calendar/event_types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("eventTypeName")));
    }

    @Test
    void unknownScheduledEventId_returns404ErrorBody() throws Exception {
        mockMvc.perform(get("/api/calendar/scheduled_events/{id}", "se_does_not_exist_aaaaaa"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not found")));
    }

    @Test
    void unknownEventTypeOnAvailableSlots_returns404ErrorBody() throws Exception {
        mockMvc.perform(get("/api/calendar/event_types/{id}/available_slots", "et_does_not_exist")
                        .param("clientTimeZone", "Europe/Berlin"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not found")));
    }

    @Test
    void invalidTimezoneOnAvailableSlots_returns400ErrorBody() throws Exception {
        mockMvc.perform(get("/api/calendar/event_types/{id}/available_slots", "et_anything")
                        .param("clientTimeZone", "Mars/Phobos"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid timezone")));
    }
}
