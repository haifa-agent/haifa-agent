package io.haifa.agent.core.error;

import static io.haifa.agent.core.support.DomainValues.optionalText;
import static io.haifa.agent.core.support.DomainValues.requireText;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Provider-neutral, persistence-safe execution failure. */
public record AgentError(AgentErrorCode code, Map<String, Object> details, String diagnosticId, Instant occurredAt) {
    private static final int MAXIMUM_DETAIL_ENTRIES = 32;
    private static final int MAXIMUM_DETAIL_KEY_CHARACTERS = 64;
    private static final int MAXIMUM_DETAIL_TEXT_CHARACTERS = 2_048;
    private static final int MAXIMUM_DETAIL_LIST_ITEMS = 32;
    private static final int MAXIMUM_DETAIL_DEPTH = 3;
    private static final int MAXIMUM_TOTAL_TEXT_CHARACTERS = 8_192;
    private static final Set<String> FORBIDDEN_DETAIL_KEY_FRAGMENTS = Set.of(
            "accesstoken",
            "apitoken",
            "authorization",
            "cookie",
            "credential",
            "password",
            "prompt",
            "reasoning",
            "refreshtoken",
            "requestbody",
            "responsebody",
            "secret",
            "sql",
            "stacktrace");

    public AgentError {
        code = Objects.requireNonNull(code, "code must not be null");
        int[] textCharacters = {0};
        details = boundedDetails(details, 0, textCharacters);
        diagnosticId = optionalText(diagnosticId);
        if (diagnosticId != null && !diagnosticId.isEmpty()) {
            diagnosticId = requireBoundedText(diagnosticId, "diagnosticId", 256, new int[] {0});
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    public String message() {
        return code.displayMessage();
    }

    public AgentErrorCategory category() {
        return code.category();
    }

    public Retryability retryability() {
        return code.retryability();
    }

    public Optional<String> optionalDiagnosticId() {
        return Optional.ofNullable(diagnosticId).filter(value -> !value.isEmpty());
    }

    private static Map<String, Object> boundedDetails(
            Map<String, Object> source, int depth, int[] totalTextCharacters) {
        Objects.requireNonNull(source, "details must not be null");
        if (source.size() > MAXIMUM_DETAIL_ENTRIES) {
            throw new IllegalArgumentException(
                    "details must not contain more than " + MAXIMUM_DETAIL_ENTRIES + " entries");
        }
        if (depth > MAXIMUM_DETAIL_DEPTH) {
            throw new IllegalArgumentException("details nesting exceeds the supported depth");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String checkedKey =
                    requireBoundedText(key, "details key", MAXIMUM_DETAIL_KEY_CHARACTERS, totalTextCharacters);
            rejectSensitiveKey(checkedKey);
            copy.put(checkedKey, boundedValue(value, depth, totalTextCharacters));
        });
        return Map.copyOf(copy);
    }

    private static Object boundedValue(Object value, int depth, int[] totalTextCharacters) {
        Objects.requireNonNull(value, "details values must not be null");
        if (value instanceof String text) {
            return requireBoundedText(text, "details text", MAXIMUM_DETAIL_TEXT_CHARACTERS, totalTextCharacters);
        }
        if (value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return value;
        }
        if (value instanceof List<?> list) {
            if (list.size() > MAXIMUM_DETAIL_LIST_ITEMS) {
                throw new IllegalArgumentException(
                        "details list must not contain more than " + MAXIMUM_DETAIL_LIST_ITEMS + " items");
            }
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(boundedValue(item, depth + 1, totalTextCharacters)));
            return List.copyOf(copy);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> nested.put(
                    Objects.toString(key, null),
                    Objects.requireNonNull(nestedValue, "details values must not be null")));
            return boundedDetails(nested, depth + 1, totalTextCharacters);
        }
        throw new IllegalArgumentException("details values must be text, boolean, integral numbers, lists, or maps");
    }

    private static String requireBoundedText(
            String value, String field, int maximumCharacters, int[] totalTextCharacters) {
        String checked = requireText(value, field);
        if (checked.length() > maximumCharacters) {
            throw new IllegalArgumentException(field + " exceeds " + maximumCharacters + " characters");
        }
        totalTextCharacters[0] = Math.addExact(totalTextCharacters[0], checked.length());
        if (totalTextCharacters[0] > MAXIMUM_TOTAL_TEXT_CHARACTERS) {
            throw new IllegalArgumentException(
                    "details text exceeds " + MAXIMUM_TOTAL_TEXT_CHARACTERS + " total characters");
        }
        return checked;
    }

    private static void rejectSensitiveKey(String key) {
        String normalized = key.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
        if (FORBIDDEN_DETAIL_KEY_FRAGMENTS.stream().anyMatch(normalized::contains)) {
            throw new IllegalArgumentException("details key is not allowed in a public execution error");
        }
    }
}
