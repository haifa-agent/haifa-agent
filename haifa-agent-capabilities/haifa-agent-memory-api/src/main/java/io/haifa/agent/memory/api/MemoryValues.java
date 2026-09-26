package io.haifa.agent.memory.api;

import java.util.Locale;
import java.util.Objects;

final class MemoryValues {
    static final int MAX_CONTENT_CHARS = 4_096;

    private MemoryValues() {}

    static String text(String value, String field, int maxLength) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > maxLength) throw new IllegalArgumentException(field + " is too long");
        return normalized;
    }

    static String subjectKey(String value) {
        return text(value, "subjectKey", 256).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    static String content(String value) {
        String normalized = text(value, "content", MAX_CONTENT_CHARS);
        String compact = normalized.replaceAll("\\s+", "");
        if (normalized.regionMatches(true, 0, "data:", 0, 5)
                && normalized.toLowerCase(Locale.ROOT).contains(";base64,")) {
            throw new IllegalArgumentException("content cannot contain raw Base64 assets");
        }
        if (compact.length() > 256 && compact.matches("[A-Za-z0-9+/]+={0,2}") && compact.length() % 4 == 0) {
            throw new IllegalArgumentException("content cannot contain raw Base64 payloads");
        }
        return normalized;
    }

    static String normalizedContent(String value) {
        return Objects.requireNonNull(value, "content must not be null").trim().replaceAll("\\s+", " ");
    }

    static int estimatedTokens(String content) {
        return Math.max(1, (content.length() + 3) / 4);
    }
}
