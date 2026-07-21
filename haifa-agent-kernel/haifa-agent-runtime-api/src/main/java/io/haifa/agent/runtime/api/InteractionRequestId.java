package io.haifa.agent.runtime.api;

import java.util.Objects;

public record InteractionRequestId(String value) {
    public InteractionRequestId {
        value = requireText(value);
    }

    private static String requireText(String value) {
        String normalized =
                Objects.requireNonNull(value, "value must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("value must not be blank");
        return normalized;
    }
}
