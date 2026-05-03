package com.hexlet.calendar.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.hexlet.calendar.domain.model.EventTypeEntity;
import com.hexlet.calendar.domain.model.ScheduledEventEntity;
import com.hexlet.calendar.generated.model.TimeSlotsOfTheDay;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
class AvailableSlotsControllerIT extends AbstractIntegrationTest {

    private static final String EVENT_TYPE_ID = "et_test_intro_aaaaaa";

    private EventTypeEntity persistEventType(int durationMinutes) {
        EventTypeEntity et = new EventTypeEntity();
        et.setId(EVENT_TYPE_ID);
        et.setName("Intro");
        et.setDescription("Short chat");
        et.setDurationMinutes(durationMinutes);
        return eventTypeRepo.save(et);
    }

    @Test
    void happyPath_returns14DayWindowOfSlots() throws Exception {
        persistEventType(30);

        MvcResult result = mockMvc.perform(get("/calendar/event_types/{id}/available_slots", EVENT_TYPE_ID)
                        .param("clientTimeZone", "Europe/Berlin"))
                .andExpect(status().isOk())
                .andReturn();

        List<TimeSlotsOfTheDay> body = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<List<TimeSlotsOfTheDay>>() {});

        // FixedClockTestConfig.FIXED_NOW = 2026-05-04T05:00:00Z (Mon Berlin) → 14-day window
        // covers Mon 5/4 through Sun 5/17, yielding 10 working days after weekends drop out.
        assertThat(body).hasSize(10);
        assertThat(body.get(0).getTimeSlots().get(0).getTimeStart()).isEqualTo("09:00:00");
        assertThat(body.get(0).getTimeSlots().get(0).getDuration()).isEqualTo(30);
    }

    @Test
    void existingBookingExcludesItsSlot() throws Exception {
        persistEventType(30);

        ScheduledEventEntity se = new ScheduledEventEntity();
        se.setId(idGen.forScheduledEvent());
        se.setEventTypeId(EVENT_TYPE_ID);
        se.setUtcStart(OffsetDateTime.of(2026, 5, 4, 8, 0, 0, 0, ZoneOffset.UTC));
        se.setUtcEnd(OffsetDateTime.of(2026, 5, 4, 8, 30, 0, 0, ZoneOffset.UTC));
        se.setDurationMinutes(30);
        se.setSubject("s");
        se.setNotes("n");
        se.setGuestName("g");
        se.setGuestEmail("g@e.com");
        se.setGuestTimezone("Europe/Berlin");
        scheduledEventRepo.save(se);

        MvcResult result = mockMvc.perform(get("/calendar/event_types/{id}/available_slots", EVENT_TYPE_ID)
                        .param("clientTimeZone", "Europe/Berlin"))
                .andExpect(status().isOk())
                .andReturn();

        List<TimeSlotsOfTheDay> body = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<List<TimeSlotsOfTheDay>>() {});

        TimeSlotsOfTheDay monday = body.stream()
                .filter(d -> d.getDate().equals(LocalDate.parse("2026-05-04")))
                .findFirst()
                .orElseThrow();

        List<String> times = monday.getTimeSlots().stream().map(ts -> ts.getTimeStart()).toList();
        assertThat(times).contains("09:30:00", "10:30:00").doesNotContain("10:00:00");
    }

    @Test
    void invalidClientTimeZone_returns400() throws Exception {
        persistEventType(30);

        mockMvc.perform(get("/calendar/event_types/{id}/available_slots", EVENT_TYPE_ID)
                        .param("clientTimeZone", "Mars/Phobos"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("Invalid timezone")));
    }

    @Test
    void unknownEventType_returns404() throws Exception {
        mockMvc.perform(get("/calendar/event_types/{id}/available_slots", "et_does_not_exist")
                        .param("clientTimeZone", "Europe/Berlin"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value(containsString("not found")));
    }

    @Test
    void weekendsHaveNoSlots() throws Exception {
        persistEventType(30);

        MvcResult result = mockMvc.perform(get("/calendar/event_types/{id}/available_slots", EVENT_TYPE_ID)
                        .param("clientTimeZone", "Europe/Berlin"))
                .andExpect(status().isOk())
                .andReturn();

        List<TimeSlotsOfTheDay> body = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<List<TimeSlotsOfTheDay>>() {});

        List<LocalDate> dates = body.stream().map(TimeSlotsOfTheDay::getDate).toList();
        assertThat(dates).doesNotContain(
                LocalDate.parse("2026-05-09"),
                LocalDate.parse("2026-05-10"));
    }
}
