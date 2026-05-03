package com.hexlet.calendar.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.hexlet.calendar.domain.model.EventTypeEntity;
import com.hexlet.calendar.generated.model.CreateScheduledEvent;
import com.hexlet.calendar.generated.model.ScheduledEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
class ScheduledEventControllerIT extends AbstractIntegrationTest {

    private static final String EVENT_TYPE_ID = "et_test_intro_aaaaaa";
    private static final String EVENT_TYPE_ID_B = "et_test_demo_bbbbbb";

    private EventTypeEntity persistEventType(String id, String name, int durationMinutes) {
        EventTypeEntity et = new EventTypeEntity();
        et.setId(id);
        et.setName(name);
        et.setDescription("d");
        et.setDurationMinutes(durationMinutes);
        return eventTypeRepo.save(et);
    }

    private CreateScheduledEvent payload(String eventTypeId, LocalDate date, String time) {
        CreateScheduledEvent p = new CreateScheduledEvent();
        p.setEventTypeId(eventTypeId);
        p.setDate(date);
        p.setTime(time);
        p.setSubject("Demo");
        p.setNotes("hi");
        p.setGuestName("Bob");
        p.setGuestEmail("bob@example.com");
        p.setGuestTimezone("Europe/Berlin");
        return p;
    }

    @Test
    void create_happyPath() throws Exception {
        persistEventType(EVENT_TYPE_ID, "Intro", 30);

        CreateScheduledEvent body = payload(EVENT_TYPE_ID, LocalDate.parse("2026-05-04"), "10:00:00");

        MvcResult result = mockMvc.perform(post("/api/calendar/scheduled_events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduledEventId").value(org.hamcrest.Matchers.matchesPattern("^se_[a-z0-9]{22}$")))
                .andExpect(jsonPath("$.utcDateStart").value("2026-05-04T08:00:00Z"))
                .andExpect(jsonPath("$.eventTypeId").value(EVENT_TYPE_ID))
                .andExpect(jsonPath("$.durationMinutes").value(30))
                .andReturn();

        ScheduledEvent created = objectMapper.readValue(
                result.getResponse().getContentAsString(), ScheduledEvent.class);

        mockMvc.perform(get("/api/calendar/scheduled_events/{id}", created.getScheduledEventId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduledEventId").value(created.getScheduledEventId()))
                .andExpect(jsonPath("$.utcDateStart").value("2026-05-04T08:00:00Z"));

        MvcResult listResult = mockMvc.perform(get("/api/calendar/scheduled_events"))
                .andExpect(status().isOk())
                .andReturn();

        List<ScheduledEvent> all = objectMapper.readValue(
                listResult.getResponse().getContentAsString(),
                new TypeReference<List<ScheduledEvent>>() {});
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getScheduledEventId()).isEqualTo(created.getScheduledEventId());
    }

    @Test
    void create_outsideWindow_returns400() throws Exception {
        persistEventType(EVENT_TYPE_ID, "Intro", 30);

        CreateScheduledEvent body = payload(EVENT_TYPE_ID, LocalDate.parse("2026-05-25"), "10:00:00");

        mockMvc.perform(post("/api/calendar/scheduled_events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("window")));
    }

    @Test
    void create_pastSlot_returns400() throws Exception {
        persistEventType(EVENT_TYPE_ID, "Intro", 30);

        CreateScheduledEvent body = payload(EVENT_TYPE_ID, LocalDate.parse("2026-05-04"), "05:30:00");

        mockMvc.perform(post("/api/calendar/scheduled_events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("past")));
    }

    @Test
    void create_misalignedSlot_returns400() throws Exception {
        persistEventType(EVENT_TYPE_ID, "Intro", 30);

        CreateScheduledEvent body = payload(EVENT_TYPE_ID, LocalDate.parse("2026-05-04"), "09:15:00");

        mockMvc.perform(post("/api/calendar/scheduled_events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("available")));
    }

    @Test
    void create_unknownEventType_returns404() throws Exception {
        CreateScheduledEvent body = payload("et_does_not_exist", LocalDate.parse("2026-05-04"), "10:00:00");

        mockMvc.perform(post("/api/calendar/scheduled_events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void create_invalidGuestTimezone_returns400() throws Exception {
        persistEventType(EVENT_TYPE_ID, "Intro", 30);

        CreateScheduledEvent body = payload(EVENT_TYPE_ID, LocalDate.parse("2026-05-04"), "10:00:00");
        body.setGuestTimezone("Mars/Phobos");

        mockMvc.perform(post("/api/calendar/scheduled_events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("guestTimezone")));
    }

    @Test
    void create_crossEventTypeCollision_returns409() throws Exception {
        persistEventType(EVENT_TYPE_ID, "Intro", 30);
        persistEventType(EVENT_TYPE_ID_B, "Demo", 30);

        CreateScheduledEvent first = payload(EVENT_TYPE_ID, LocalDate.parse("2026-05-04"), "10:00:00");
        mockMvc.perform(post("/api/calendar/scheduled_events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isOk());

        CreateScheduledEvent second = payload(EVENT_TYPE_ID_B, LocalDate.parse("2026-05-04"), "10:00:00");
        mockMvc.perform(post("/api/calendar/scheduled_events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("Slot is no longer available"));
    }

    @Test
    void getById_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/calendar/scheduled_events/{id}", "se_does_not_exist_aaaaaaa"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void list_returnsAscByUtcStart() throws Exception {
        persistEventType(EVENT_TYPE_ID, "Intro", 30);

        // Insert directly to bypass alignment ordering — we want the *list endpoint* sort,
        // not the create-flow checks.
        scheduledEventRepo.save(buildSe("se_b_2222222222222222222222",
                OffsetDateTime.of(2026, 5, 4, 11, 0, 0, 0, ZoneOffset.UTC)));
        scheduledEventRepo.save(buildSe("se_a_1111111111111111111111",
                OffsetDateTime.of(2026, 5, 4, 9, 0, 0, 0, ZoneOffset.UTC)));
        scheduledEventRepo.save(buildSe("se_c_3333333333333333333333",
                OffsetDateTime.of(2026, 5, 4, 10, 0, 0, 0, ZoneOffset.UTC)));

        MvcResult result = mockMvc.perform(get("/api/calendar/scheduled_events"))
                .andExpect(status().isOk())
                .andReturn();

        List<ScheduledEvent> all = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<List<ScheduledEvent>>() {});

        assertThat(all).hasSize(3);
        assertThat(all.get(0).getUtcDateStart().toInstant())
                .isBefore(all.get(1).getUtcDateStart().toInstant());
        assertThat(all.get(1).getUtcDateStart().toInstant())
                .isBefore(all.get(2).getUtcDateStart().toInstant());
    }

    private com.hexlet.calendar.domain.model.ScheduledEventEntity buildSe(String id, OffsetDateTime start) {
        com.hexlet.calendar.domain.model.ScheduledEventEntity se =
                new com.hexlet.calendar.domain.model.ScheduledEventEntity();
        se.setId(id);
        se.setEventTypeId(EVENT_TYPE_ID);
        se.setUtcStart(start);
        se.setUtcEnd(start.plusMinutes(30));
        se.setDurationMinutes(30);
        se.setSubject("s");
        se.setNotes("n");
        se.setGuestName("g");
        se.setGuestEmail("g@e.com");
        se.setGuestTimezone("Europe/Berlin");
        return se;
    }
}
