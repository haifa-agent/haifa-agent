package io.haifa.agent.tool.api;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            return canonicalNumber(number);
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

    /**
     * Canonicalises a JSON number to the exact boxed type a JSON round trip produces.
     *
     * <p>JSON text carries no integer width, so a persisted Tool schema document read back from the
     * runtime store returns an in-range integral value as {@link Integer} rather than the declared
     * {@link Long}. Keeping the declared value in the same shape avoids spurious "binding differs
     * from the frozen catalog" failures when a Run resumes, without changing the canonical JSON
     * digest: {@code 600000} renders identically for both widths.
     */
    private static Number canonicalNumber(Number value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return value.intValue();
        }
        if (value instanceof Long longValue) {
            if (longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
                return Integer.valueOf(longValue.intValue());
            }
            return longValue;
        }
        if (value instanceof AtomicInteger atomicInteger) {
            return atomicInteger.get();
        }
        if (value instanceof AtomicLong atomicLong) {
            return canonicalNumber(atomicLong.get());
        }
        if (value instanceof BigInteger bigInteger) {
            if (bigInteger.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) >= 0
                    && bigInteger.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0) {
                return bigInteger.intValue();
            }
            if (bigInteger.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0
                    && bigInteger.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0) {
                return bigInteger.longValue();
            }
            return bigInteger;
        }
        if (value instanceof Float || value instanceof Double) {
            return value.doubleValue();
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal.doubleValue();
        }
        // An unexpected Number subtype still has one exact JSON value; keep it comparable.
        return value.doubleValue();
    }
}
