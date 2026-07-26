package io.haifa.agent.policy.api;

import java.util.Objects;
import java.util.Optional;

final class PolicyValues {
    static final int MAX_IDENTIFIER_LENGTH = 256;
    static final int MAX_SAFE_TEXT_LENGTH = 512;

    private PolicyValues() {}

    static String requireIdentifier(String value, String field) {
        return requireText(value, field, MAX_IDENTIFIER_LENGTH);
    }

    static String requireSafeText(String value, String field) {
        return requireText(value, field, MAX_SAFE_TEXT_LENGTH);
    }

    static String requireText(String value, String field, int maximumLength) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds maximum length");
        }
        return normalized;
    }

    static Optional<String> optionalIdentifier(Optional<String> value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        return value.map(item -> requireIdentifier(item, field));
    }

    static Optional<String> optionalSafeText(Optional<String> value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        return value.map(item -> requireSafeText(item, field));
    }
}
