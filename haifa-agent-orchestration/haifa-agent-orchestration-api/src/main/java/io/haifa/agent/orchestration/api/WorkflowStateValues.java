package io.haifa.agent.orchestration.api;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class WorkflowStateValues {
    private WorkflowStateValues() {}

    static Map<String, Object> freeze(
            Map<String, ?> values, int maximumValues, int maximumDepth, int maximumStringLength) {
        ValueBudget budget = new ValueBudget(maximumValues);
        Map<String, Object> result = new LinkedHashMap<>();
        new TreeMap<>(values)
                .forEach((key, value) -> result.put(
                        Objects.requireNonNull(key, "state key must not be null"),
                        freezeValue(value, 1, maximumDepth, maximumStringLength, budget)));
        return Collections.unmodifiableMap(result);
    }

    private static Object freezeValue(
            Object value, int depth, int maximumDepth, int maximumStringLength, ValueBudget budget) {
        Objects.requireNonNull(value, "state value must not be null");
        budget.consume();
        if (depth > maximumDepth) {
            throw new IllegalArgumentException("workflow state exceeds maximum depth");
        }
        if (value instanceof String string) {
            if (string.length() > maximumStringLength) {
                throw new IllegalArgumentException("workflow state string exceeds maximum length");
            }
            return string;
        }
        if (value instanceof Boolean
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger
                || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nestedValue) -> {
                if (!(key instanceof String stringKey) || stringKey.isBlank()) {
                    throw new IllegalArgumentException("nested workflow state keys must be strings");
                }
                sorted.put(stringKey, nestedValue);
            });
            sorted.forEach((key, nestedValue) ->
                    nested.put(key, freezeValue(nestedValue, depth + 1, maximumDepth, maximumStringLength, budget)));
            return Collections.unmodifiableMap(nested);
        }
        if (value instanceof List<?> list) {
            List<Object> nested = new ArrayList<>(list.size());
            list.forEach(item -> nested.add(freezeValue(item, depth + 1, maximumDepth, maximumStringLength, budget)));
            return Collections.unmodifiableList(nested);
        }
        throw new IllegalArgumentException(
                "unsupported workflow state value type: " + value.getClass().getName());
    }

    private static final class ValueBudget {
        private int remaining;

        private ValueBudget(int maximumValues) {
            this.remaining = maximumValues;
        }

        private void consume() {
            if (--remaining < 0) {
                throw new IllegalArgumentException("workflow state exceeds maximum values");
            }
        }
    }
}
