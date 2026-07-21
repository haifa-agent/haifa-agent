package io.haifa.agent.runtime.core.decision;

import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import java.util.Objects;

public record ToolRequest(
        ToolCallId toolCallId,
        ProviderToolCallCorrelationId providerCorrelationId,
        RuntimeIdempotencyKey idempotencyKey,
        String toolName,
        String toolVersion,
        ToolArguments arguments) {
    public ToolRequest {
        toolCallId = Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        providerCorrelationId = Objects.requireNonNull(providerCorrelationId, "providerCorrelationId must not be null");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        toolName = requireText(toolName, "toolName");
        toolVersion = requireText(toolVersion, "toolVersion");
        arguments = Objects.requireNonNull(arguments, "arguments must not be null");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
