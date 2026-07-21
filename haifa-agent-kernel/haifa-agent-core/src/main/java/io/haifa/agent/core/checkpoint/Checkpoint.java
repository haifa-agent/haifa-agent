package io.haifa.agent.core.checkpoint;

import static io.haifa.agent.core.support.DomainValues.requireText;

import io.haifa.agent.core.reference.CheckpointPayloadRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStepId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Immutable append-only reference to a recoverable run state. */
public record Checkpoint(
        CheckpointId id,
        AgentRunId runId,
        Optional<AgentStepId> stepId,
        CheckpointType type,
        CheckpointStatus status,
        long sequence,
        CheckpointPayloadRef payload,
        String stateHash,
        Instant createdAt) {
    public Checkpoint {
        id = Objects.requireNonNull(id, "id must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        stepId = Objects.requireNonNull(stepId, "stepId must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        if (sequence < 1) {
            throw new IllegalArgumentException("checkpoint sequence must be positive");
        }
        payload = Objects.requireNonNull(payload, "payload must not be null");
        stateHash = requireText(stateHash, "stateHash");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
