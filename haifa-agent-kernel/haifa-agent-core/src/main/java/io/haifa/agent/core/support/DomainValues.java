package io.haifa.agent.core.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Shared validation and defensive-copy helpers for Core value objects. */
public final class DomainValues {

    private DomainValues() {}

    public static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    public static String optionalText(String value) {
        return value == null ? null : value.trim();
    }

    public static Map<String, Object> immutableMap(Map<String, Object> source, String field) {
        Objects.requireNonNull(source, field + " must not be null");
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(requireText(key, field + " key"), immutableValue(value, field)));
        return Map.copyOf(copy);
    }

    private static Object immutableValue(Object value, String field) {
        Objects.requireNonNull(value, field + " values must not be null");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(
                    requireText(Objects.toString(key, null), field + " nested key"), immutableValue(nested, field)));
            return Map.copyOf(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableValue(item, field)));
            return List.copyOf(copy);
        }
        if (value instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>();
            set.forEach(item -> copy.add(immutableValue(item, field)));
            return Set.copyOf(copy);
        }
        return value;
    }
}
