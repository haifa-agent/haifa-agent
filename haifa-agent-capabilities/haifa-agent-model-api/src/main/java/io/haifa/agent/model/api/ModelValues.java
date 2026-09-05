package io.haifa.agent.model.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ModelValues {
    private ModelValues() {}

    static String text(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    static Map<String, Object> map(Map<String, Object> value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        value.forEach((key, item) -> copy.put(text(key, field + " key"), immutable(item, field)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutable(Object value, String field) {
        Objects.requireNonNull(value, field + " value must not be null");
        if (value instanceof Map<?, ?> source) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            source.forEach((key, item) -> copy.put(text(String.valueOf(key), field + " key"), immutable(item, field)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> source) {
            return source.stream().map(item -> immutable(item, field)).toList();
        }
        if (value instanceof Set<?> source) {
            LinkedHashSet<Object> copy = new LinkedHashSet<>();
            source.forEach(item -> copy.add(immutable(item, field)));
            return Collections.unmodifiableSet(copy);
        }
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof java.math.BigInteger) {
            try {
                return new java.math.BigInteger(value.toString()).longValueExact();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(field + " integer value exceeds the supported range", exception);
            }
        }
        if (value instanceof Float || value instanceof Double || value instanceof java.math.BigDecimal) {
            double decimal = Double.parseDouble(value.toString());
            if (!Double.isFinite(decimal)) {
                throw new IllegalArgumentException(field + " decimal value must be finite");
            }
            return decimal;
        }
        if (value instanceof String
                || value instanceof Boolean
                || value instanceof Enum<?>
                || value instanceof java.net.URI
                || value instanceof java.time.Duration) {
            return value;
        }
        throw new IllegalArgumentException(
                "unsupported " + field + " value type: " + value.getClass().getName());
    }
}
