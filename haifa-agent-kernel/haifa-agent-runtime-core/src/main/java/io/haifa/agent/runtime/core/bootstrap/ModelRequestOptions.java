package io.haifa.agent.runtime.core.bootstrap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Deeply immutable and deterministic representation of provider-neutral model request options. */
final class ModelRequestOptions {
    private ModelRequestOptions() {}

    static Map<String, Object> freeze(Map<String, Object> source) {
        Map<String, Object> values = new LinkedHashMap<>();
        Objects.requireNonNull(source, "modelRequestOptions must not be null").entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> values.put(requireKey(entry.getKey()), freezeValue(entry.getValue())));
        return Map.copyOf(values);
    }

    static String canonical(Map<String, Object> source) {
        return canonicalValue(freeze(source));
    }

    private static Object freezeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry ->
                            nested.put(requireKey(String.valueOf(entry.getKey())), freezeValue(entry.getValue())));
            return Map.copyOf(nested);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> nested = new ArrayList<>();
            iterable.forEach(item -> nested.add(freezeValue(item)));
            return List.copyOf(nested);
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw new IllegalArgumentException("modelRequestOptions values must be JSON-compatible and non-null");
    }

    private static String canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .map(entry -> String.valueOf(entry.getKey()) + "=" + canonicalValue(entry.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> items = new ArrayList<>();
            iterable.forEach(item -> items.add(canonicalValue(item)));
            return String.join(",", items);
        }
        return String.valueOf(value);
    }

    private static String requireKey(String value) {
        String normalized = Objects.requireNonNull(value, "modelRequestOptions key must not be null")
                .trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("modelRequestOptions key must not be blank");
        return normalized;
    }
}
