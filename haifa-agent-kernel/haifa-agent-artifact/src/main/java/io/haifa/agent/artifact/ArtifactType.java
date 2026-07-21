package io.haifa.agent.artifact;

import java.util.Objects;

public record ArtifactType(String value) {
    public ArtifactType {
        value = Objects.requireNonNull(value, "value must not be null").trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[a-z][a-z0-9.-]{0,63}")) throw new IllegalArgumentException("invalid artifact type");
    }
}
