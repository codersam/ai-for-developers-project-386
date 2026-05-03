package com.hexlet.calendar.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class SpaFallbackController {

    // Mirrors the React Router route prefixes in frontend/src/routes/index.tsx.
    // Spring's static resource handler serves "/" → index.html for free, so the
    // root route doesn't need a forward. Unmapped paths (e.g. /api/foo, /typo)
    // fall through to a normal 404 instead of being silently swallowed.
    @GetMapping(value = {"/admin/**", "/book/**"})
    String forward() {
        return "forward:/index.html";
    }
}
