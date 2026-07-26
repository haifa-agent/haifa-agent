package io.haifa.agent.runtime.api;

import java.util.Objects;

/** Stable option identifier plus display-only label. */
public record InteractionOption(String id, String label) {
    public InteractionOption {
        id = InteractionKind.requireToken(id, "id");
        label = requireText(label, "label", 256);
    }

    static String requireText(String value, String field, int maximumLength) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must contain 1.." + maximumLength + " characters");
        }
        return normalized;
    }
}
