package io.haifa.agent.runtime.core.tool;

import java.util.Objects;

public record ToolDefinition(String name, String version, String inputSchemaId, boolean sideEffecting) {
    public ToolDefinition {
        name = requireText(name, "name");
        version = requireText(version, "version");
        inputSchemaId = requireText(inputSchemaId, "inputSchemaId");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
