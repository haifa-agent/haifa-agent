package io.haifa.agent.tool.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ToolValues {
    private ToolValues() {}

    static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static <T> Set<T> set(Set<T> value, String name) {
        Objects.requireNonNull(value, name);
        var copy = new LinkedHashSet<T>();
        for (T element : value) {
            copy.add(Objects.requireNonNull(element, name + " element"));
        }
        return Collections.unmodifiableSet(copy);
    }

    static Map<String, Object> jsonObject(Map<String, ?> value, String name) {
        Objects.requireNonNull(value, name);
        var copy = new LinkedHashMap<String, Object>();
        value.forEach((key, element) -> copy.put(text(key, name + " key"), jsonValue(element, name)));
        return Collections.unmodifiableMap(copy);
    }

    @SuppressWarnings("unchecked")
    static Object jsonValue(Object value, String name) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            map.forEach((key, element) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException(name + " JSON object keys must be strings");
                }
                copy.put(stringKey, jsonValue(element, name));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<>();
            list.forEach(element -> copy.add(jsonValue(element, name)));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException(
                name + " contains unsupported JSON value " + value.getClass().getName());
    }
}
