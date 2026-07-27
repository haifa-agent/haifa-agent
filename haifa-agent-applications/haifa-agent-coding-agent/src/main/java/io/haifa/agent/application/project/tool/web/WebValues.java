package io.haifa.agent.application.project.tool.web;

import java.net.IDN;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class WebValues {
    private WebValues() {}

    static String text(String value, String field, int maxLength) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > maxLength) throw new IllegalArgumentException(field + " is too long");
        return normalized;
    }

    static Optional<String> optionalText(Optional<String> value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must not be null");
        return value.map(item -> text(item, field, maxLength));
    }

    static List<String> domains(List<String> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        if (values.size() > 50) throw new IllegalArgumentException(field + " contains too many domains");
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String domain = text(value, field, 253).toLowerCase(Locale.ROOT);
            if (domain.contains("://")
                    || domain.contains("/")
                    || domain.contains(":")
                    || domain.startsWith(".")
                    || domain.endsWith(".")) {
                throw new IllegalArgumentException(field + " contains an invalid domain");
            }
            try {
                domain = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(field + " contains an invalid domain", exception);
            }
            if (domain.length() > 253) throw new IllegalArgumentException(field + " contains an invalid domain");
            normalized.add(domain);
        }
        return List.copyOf(new ArrayList<>(normalized));
    }

    static Set<String> strings(Set<String> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) normalized.add(text(value, field, 256));
        return Set.copyOf(normalized);
    }

    static Map<String, String> stringMap(Map<String, String> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> result.put(text(key, field + " key", 128), text(value, field + " value", 1024)));
        return Map.copyOf(result);
    }
}
