package io.haifa.agent.auth.localmodel;

import java.util.Objects;
import java.util.UUID;

/** Caller-generated UUIDv7 identifier. This type never reads time or randomness. */
public record ExternalLoginAttemptId(String value) {
    public ExternalLoginAttemptId {
        value = Objects.requireNonNull(value, "value must not be null").trim().toLowerCase(java.util.Locale.ROOT);
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("external login attempt id must be UUIDv7", exception);
        }
        if (parsed.version() != 7 || !parsed.toString().equals(value)) {
            throw new IllegalArgumentException("external login attempt id must be UUIDv7");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
