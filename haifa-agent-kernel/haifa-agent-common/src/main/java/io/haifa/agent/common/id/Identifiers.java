package io.haifa.agent.common.id;

import java.util.Objects;
import java.util.UUID;

/** Identifier validation and generation utilities shared by domain modules. */
public final class Identifiers {

    private Identifiers() {}

    public static String randomValue() {
        return UUID.randomUUID().toString();
    }

    public static String requireValid(String value, String fieldName) {
        String normalized =
                Objects.requireNonNull(value, fieldName + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
