package io.haifa.agent.orchestration.api;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record WorkflowNodeAttempt(
        WorkflowNodeId nodeId,
        int attempt,
        WorkflowNodeAttemptStatus status,
        Optional<AgentRunId> agentRunId,
        Optional<WorkflowErrorCode> failureCode,
        Instant startedAt,
        Optional<Instant> finishedAt) {
    public WorkflowNodeAttempt {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        Objects.requireNonNull(status, "status must not be null");
        agentRunId = Objects.requireNonNull(agentRunId, "agentRunId must not be null");
        failureCode = Objects.requireNonNull(failureCode, "failureCode must not be null");
        if ((status == WorkflowNodeAttemptStatus.FAILED || status == WorkflowNodeAttemptStatus.OUTCOME_UNKNOWN)
                != failureCode.isPresent()) {
            throw new IllegalArgumentException("failed attempt status must have exactly one failure code");
        }
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        finishedAt = Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        if ((status == WorkflowNodeAttemptStatus.RUNNING) == finishedAt.isPresent()) {
            throw new IllegalArgumentException("only a running attempt may omit finishedAt");
        }
    }
}
