package io.haifa.agent.runtime.api;

import io.haifa.agent.core.checkpoint.CheckpointId;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.run.AgentRunId;
import java.util.List;
import java.util.Objects;

/** Request to resume a suspended or waiting run with optional additional input. */
public record ResumeAgentRunRequest(
        String idempotencyKey,
        AgentRunId runId,
        java.util.Optional<CheckpointId> checkpointId,
        List<ContentPart> inputs) {

    public ResumeAgentRunRequest {
        idempotencyKey = requireText(idempotencyKey);
        runId = Objects.requireNonNull(runId, "runId must not be null");
        checkpointId = Objects.requireNonNull(checkpointId, "checkpointId must not be null");
        Objects.requireNonNull(inputs, "inputs must not be null");
        if (inputs.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("inputs must not contain null");
        }
        inputs = List.copyOf(inputs);
    }

    public ResumeAgentRunRequest(String idempotencyKey, AgentRunId runId, List<ContentPart> inputs) {
        this(idempotencyKey, runId, java.util.Optional.empty(), inputs);
    }

    public static ResumeAgentRunRequest withoutInput(AgentRunId runId) {
        return new ResumeAgentRunRequest("resume-" + runId.value(), runId, java.util.Optional.empty(), List.of());
    }

    private static String requireText(String value) {
        String normalized =
                Objects.requireNonNull(value, "idempotencyKey must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        return normalized;
    }
}
