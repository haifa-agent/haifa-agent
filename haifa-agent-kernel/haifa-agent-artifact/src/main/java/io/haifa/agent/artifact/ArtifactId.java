package io.haifa.agent.artifact;

import io.haifa.agent.common.id.Identifier;
import java.util.Objects;

public record ArtifactId(String value) implements Identifier {
    public ArtifactId {
        value = require(value, "value");
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
