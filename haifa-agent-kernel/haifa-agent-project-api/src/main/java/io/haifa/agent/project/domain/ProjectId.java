package io.haifa.agent.project.domain;

import io.haifa.agent.common.id.Identifier;
import java.util.Objects;

public record ProjectId(String value) implements Identifier {
    public ProjectId {
        value = requireText(value, "value");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
