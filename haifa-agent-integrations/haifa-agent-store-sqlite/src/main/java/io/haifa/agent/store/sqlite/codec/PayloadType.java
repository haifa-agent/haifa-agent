package io.haifa.agent.store.sqlite.codec;

import java.util.Objects;

public record PayloadType<T>(String name, String schemaVersion, Class<T> dtoType) {
    public PayloadType {
        name = requireText(name, "name");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        dtoType = Objects.requireNonNull(dtoType, "dtoType must not be null");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
