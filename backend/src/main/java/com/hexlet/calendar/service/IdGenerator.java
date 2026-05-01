package com.hexlet.calendar.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class IdGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final int SLUG_MAX = 32;

    public String forEventType(String name) {
        return "et_" + slugify(name) + "_" + suffix(6);
    }

    public String forScheduledEvent() {
        return "se_" + suffix(22);
    }

    private static String slugify(String input) {
        if (input == null) {
            return "evt";
        }
        String slug = input.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("^-+", "").replaceAll("-+$", "");
        if (slug.length() > SLUG_MAX) {
            slug = slug.substring(0, SLUG_MAX);
            slug = slug.replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "evt" : slug;
    }

    private static String suffix(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
