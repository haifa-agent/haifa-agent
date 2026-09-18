package io.haifa.agent.core.tool;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStepId;
import java.time.Instant;

/** Immutable, versioned persistence contract for controlled {@link ToolCall} reconstitution. */
public record ToolCallPersistenceSnapshot(
        String schemaVersion,
        ToolCallId id,
        AgentRunId runId,
        AgentStepId stepId,
        ProviderToolCallCorrelationId providerCorrelationId,
        RuntimeIdempotencyKey idempotencyKey,
        String toolName,
        String toolVersion,
        ToolArguments arguments,
        Instant requestedAt,
        String status,
        Instant startedAt,
        Instant completedAt,
        ToolResult result,
        ToolExecutionError error,
        long version) {}
