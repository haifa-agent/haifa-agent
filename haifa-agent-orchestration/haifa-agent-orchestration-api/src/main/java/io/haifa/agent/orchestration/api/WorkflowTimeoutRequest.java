package io.haifa.agent.orchestration.api;

import io.haifa.agent.common.id.Identifiers;
import java.util.Objects;

/** Trusted scheduler command that terminates a workflow and its active subgraph tree as timed out. */
public record WorkflowTimeoutRequest(WorkflowRunId runId, String idempotencyKey) {
    public WorkflowTimeoutRequest {
        Objects.requireNonNull(runId, "runId must not be null");
        idempotencyKey = Identifiers.requireValid(idempotencyKey, "idempotencyKey");
    }
}
