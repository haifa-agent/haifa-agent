package io.haifa.agent.runtime.core.decision;

import io.haifa.agent.core.tool.ToolArguments;
import java.util.Objects;

public record ToolRequest(String toolName, String toolVersion, ToolArguments arguments, String idempotencyKey) {
    public ToolRequest {
        toolName = requireText(toolName, "toolName");
        toolVersion = requireText(toolVersion, "toolVersion");
        arguments = Objects.requireNonNull(arguments, "arguments must not be null");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
