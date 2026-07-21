package io.haifa.agent.memory.api;

import java.util.Objects;

final class MemoryValues {
    private MemoryValues() {}

    static String text(String value, String field, int maxLength) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > maxLength) throw new IllegalArgumentException(field + " is too long");
        return normalized;
    }

    static String content(String value, String field, int maxLength) {
        String normalized = text(value, field, maxLength);
        String compact = normalized.replaceAll("\\s+", "");
        if (normalized.regionMatches(true, 0, "data:", 0, 5)
                && normalized.toLowerCase().contains(";base64,")) {
            throw new IllegalArgumentException(field + " cannot contain raw Base64 assets");
        }
        if (compact.length() > 256 && compact.matches("[A-Za-z0-9+/]+={0,2}") && compact.length() % 4 == 0) {
            throw new IllegalArgumentException(field + " cannot contain raw Base64 payloads");
        }
        return normalized;
    }
}
