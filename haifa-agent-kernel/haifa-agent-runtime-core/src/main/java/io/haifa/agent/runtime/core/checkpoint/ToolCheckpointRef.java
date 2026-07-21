package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolCallStatus;
import java.util.Objects;

public record ToolCheckpointRef(
        ToolCallId toolCallId,
        ProviderToolCallCorrelationId providerCorrelationId,
        RuntimeIdempotencyKey idempotencyKey,
        ToolCallStatus status,
        long version) {
    public ToolCheckpointRef {
        toolCallId = Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        providerCorrelationId = Objects.requireNonNull(providerCorrelationId, "providerCorrelationId must not be null");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        if (version < 0) throw new IllegalArgumentException("tool version must not be negative");
    }
}
