package io.haifa.agent.runtime.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class RuntimeValues {
    private RuntimeValues() {}

    static Map<String, Object> immutableMap(Map<String, Object> source, String field) {
        Objects.requireNonNull(source, field + " must not be null");
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(requireKey(key, field), immutable(value, field)));
        return Map.copyOf(copy);
    }

    private static Object immutable(Object value, String field) {
        Objects.requireNonNull(value, field + " values must not be null");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) ->
                    copy.put(requireKey(Objects.toString(key, null), field), immutable(nested, field)));
            return Map.copyOf(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(immutable(item, field)));
            return List.copyOf(copy);
        }
        if (value instanceof Set<?> set) {
            java.util.LinkedHashSet<Object> copy = new java.util.LinkedHashSet<>();
            set.forEach(item -> copy.add(immutable(item, field)));
            return Set.copyOf(copy);
        }
        return value;
    }

    private static String requireKey(String key, String field) {
        String normalized =
                Objects.requireNonNull(key, field + " key must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " key must not be blank");
        }
        return normalized;
    }
}
