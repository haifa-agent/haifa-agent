package io.haifa.agent.contract.common;

import java.util.Objects;

public record CorrelationId(String value) {
    public CorrelationId {
        value = requireText(value, "value", 256);
    }

    public static String requireText(String value, String field, int maximumLength) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must contain 1.." + maximumLength + " characters");
        }
        return normalized;
    }
}
