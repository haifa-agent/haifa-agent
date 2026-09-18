package io.haifa.agent.contract.run;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Safe, transport-neutral projection of an Agent execution failure. */
public record AgentExecutionErrorView(
        String code,
        String message,
        String category,
        String retryability,
        Map<String, Object> details,
        Optional<String> diagnosticId,
        Instant occurredAt) {
    private static final int MAXIMUM_DETAIL_ENTRIES = 32;
    private static final int MAXIMUM_DETAIL_LIST_ITEMS = 32;
    private static final int MAXIMUM_DETAIL_DEPTH = 3;
    private static final int MAXIMUM_DETAIL_TEXT_CHARACTERS = 2_048;

    public AgentExecutionErrorView {
        code = require(code, "code", 128);
        message = require(message, "message", 2_048);
        category = require(category, "category", 64);
        retryability = require(retryability, "retryability", 64);
        details = immutableDetails(details);
        diagnosticId = Objects.requireNonNull(diagnosticId, "diagnosticId must not be null")
                .map(value -> require(value, "diagnosticId", 256));
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    private static String require(String value, String field, int maximumLength) {
        return io.haifa.agent.contract.common.CorrelationId.requireText(value, field, maximumLength);
    }

    private static Map<String, Object> immutableDetails(Map<String, Object> source) {
        return immutableDetails(source, 0);
    }

    private static Map<String, Object> immutableDetails(Map<String, Object> source, int depth) {
        Objects.requireNonNull(source, "details must not be null");
        if (source.size() > MAXIMUM_DETAIL_ENTRIES || depth > MAXIMUM_DETAIL_DEPTH) {
            throw new IllegalArgumentException("details exceed the public contract bounds");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(require(key, "details key", 64), immutableDetailValue(value, depth)));
        return Map.copyOf(copy);
    }

    private static Object immutableDetailValue(Object value, int depth) {
        Objects.requireNonNull(value, "details values must not be null");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, nestedValue) ->
                    nested.put(require(Objects.toString(key, null), "details nested key", 64), nestedValue));
            return immutableDetails(nested, depth + 1);
        }
        if (value instanceof List<?> list) {
            if (list.size() > MAXIMUM_DETAIL_LIST_ITEMS || depth >= MAXIMUM_DETAIL_DEPTH) {
                throw new IllegalArgumentException("details list exceeds the public contract bounds");
            }
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableDetailValue(item, depth + 1)));
            return List.copyOf(copy);
        }
        if (value instanceof String text) {
            return require(text, "details text", MAXIMUM_DETAIL_TEXT_CHARACTERS);
        }
        if (value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return value;
        }
        throw new IllegalArgumentException("details contain an unsupported value type");
    }
}
