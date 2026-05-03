package com.hexlet.calendar.web;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
class SpaFallbackControllerIT extends AbstractIntegrationTest {

    @Test
    void adminRoute_forwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/admin/event-types")).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/admin/bookings")).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void bookRoute_forwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/book/et_intro-call_abc123")).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void apiPath_isNotForwarded_handledByCalendarController() throws Exception {
        mockMvc.perform(get("/api/calendar/event_types"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl(null));
    }

    @Test
    void unknownTopLevelPath_returns404_notForwarded() throws Exception {
        // Non-SPA prefixes (e.g. typos) must fall through to a typed 404 Error body,
        // not be silently masked by the fallback nor surface as 500. Anchors both
        // the prefix-matching contract and the NoResourceFoundException → 404 mapping
        // in ApiExceptionHandler.
        mockMvc.perform(get("/typo"))
                .andExpect(status().isNotFound())
                .andExpect(forwardedUrl(null));
    }

    @Test
    void unmappedApiPath_returns404_notForwarded() throws Exception {
        // /api/foo is not a real endpoint; broken API clients must receive 404 JSON,
        // not HTML and not 500.
        mockMvc.perform(get("/api/this-endpoint-does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(forwardedUrl(null));
    }
}
