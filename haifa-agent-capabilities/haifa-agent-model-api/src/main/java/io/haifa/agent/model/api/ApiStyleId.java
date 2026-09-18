package io.haifa.agent.model.api;

import java.util.Objects;

/** Stable identifier for a public model API protocol family. */
public record ApiStyleId(String value) implements Comparable<ApiStyleId> {
    public ApiStyleId {
        value = Objects.requireNonNull(value, "value must not be null").trim();
        if (!value.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("API style must be a lower-case kebab-case identifier");
        }
    }

    @Override
    public int compareTo(ApiStyleId other) {
        return value.compareTo(Objects.requireNonNull(other, "other must not be null").value);
    }

    @Override
    public String toString() {
        return value;
    }
}
