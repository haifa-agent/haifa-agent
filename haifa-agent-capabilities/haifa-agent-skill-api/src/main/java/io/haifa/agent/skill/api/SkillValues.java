package io.haifa.agent.skill.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class SkillValues {
    private SkillValues() {}

    static String text(String value, String field, int maxLength) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > maxLength) throw new IllegalArgumentException(field + " is too long");
        if (normalized.chars().anyMatch(character -> character == 0 || character == '\r')) {
            throw new IllegalArgumentException(field + " contains a forbidden control character");
        }
        return normalized;
    }

    static Map<String, String> stringMap(Map<String, String> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        if (values.size() > 64) throw new IllegalArgumentException(field + " has too many entries");
        Map<String, String> copy = new LinkedHashMap<>();
        int total = 0;
        for (var entry : values.entrySet()) {
            String key = text(entry.getKey(), field + " key", 128);
            String value = text(entry.getValue(), field + " value", 4096);
            total += key.length() + value.length();
            if (total > 32_768) throw new IllegalArgumentException(field + " is too large");
            if (copy.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException(field + " contains a duplicate key");
            }
        }
        return Map.copyOf(copy);
    }
}
