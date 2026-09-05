package io.haifa.agent.orchestration.api;

import io.haifa.agent.common.id.Identifiers;
import java.util.Objects;

public record WorkflowCancelRequest(WorkflowRunId runId, String idempotencyKey) {
    public WorkflowCancelRequest {
        Objects.requireNonNull(runId, "runId must not be null");
        idempotencyKey = Identifiers.requireValid(idempotencyKey, "idempotencyKey");
    }
}
