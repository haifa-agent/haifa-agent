package io.haifa.agent.runtime.api;

import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolCallStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Caller-scoped, read-only projection of one authoritative persisted Tool Call. */
public record ToolCallView(
        ToolCallId id,
        AgentRunId runId,
        String toolName,
        String toolVersion,
        ToolDataView arguments,
        ToolCallStatus status,
        Optional<ToolResultView> result,
        Optional<AgentErrorCode> error,
        Instant requestedAt,
        Optional<Instant> startedAt,
        Optional<Instant> completedAt) {
    public ToolCallView {
        id = Objects.requireNonNull(id, "id must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        toolName = requireText(toolName, "toolName");
        toolVersion = requireText(toolVersion, "toolVersion");
        arguments = Objects.requireNonNull(arguments, "arguments must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        result = Objects.requireNonNull(result, "result must not be null");
        error = Objects.requireNonNull(error, "error must not be null");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    private static String requireText(String value, String field) {
        String checked =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (checked.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return checked;
    }
}
