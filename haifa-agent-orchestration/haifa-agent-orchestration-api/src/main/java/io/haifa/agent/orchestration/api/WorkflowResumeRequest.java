package io.haifa.agent.orchestration.api;

import io.haifa.agent.common.id.Identifiers;
import java.util.Objects;

public record WorkflowResumeRequest(
        WorkflowRunId runId,
        WorkflowWaitId waitId,
        long expectedRevision,
        WorkflowSignalId signalId,
        String idempotencyKey,
        WorkflowStateDelta delta) {
    public WorkflowResumeRequest {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(waitId, "waitId must not be null");
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        Objects.requireNonNull(signalId, "signalId must not be null");
        idempotencyKey = Identifiers.requireValid(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(delta, "delta must not be null");
    }
}
