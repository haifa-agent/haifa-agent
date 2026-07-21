package io.haifa.agent.common.id;

import java.util.Objects;

/** Identifier validation utilities shared by domain modules. */
public final class Identifiers {

    private Identifiers() {}

    public static String requireValid(String value, String fieldName) {
        String normalized =
                Objects.requireNonNull(value, fieldName + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
