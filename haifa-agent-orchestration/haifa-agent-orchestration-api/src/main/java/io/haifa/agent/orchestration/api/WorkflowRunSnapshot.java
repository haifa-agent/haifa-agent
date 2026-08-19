package io.haifa.agent.orchestration.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record WorkflowRunSnapshot(
        WorkflowRunId id,
        WorkflowDefinitionRef definition,
        WorkflowStatus status,
        long revision,
        WorkflowState state,
        Optional<WorkflowNodeId> currentNode,
        Optional<WorkflowWait> activeWait,
        Optional<WorkflowCheckpoint> checkpoint,
        Optional<WorkflowFailure> failure,
        List<WorkflowNodeAttempt> attempts,
        Instant createdAt,
        Instant updatedAt) {
    public WorkflowRunSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        Objects.requireNonNull(state, "state must not be null");
        currentNode = Objects.requireNonNull(currentNode, "currentNode must not be null");
        activeWait = Objects.requireNonNull(activeWait, "activeWait must not be null");
        checkpoint = Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        failure = Objects.requireNonNull(failure, "failure must not be null");
        attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts must not be null"));
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
