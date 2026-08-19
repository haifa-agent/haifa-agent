package io.haifa.agent.orchestration.api;

import io.haifa.agent.common.id.Identifiers;
import java.util.Objects;

public record WorkflowStartRequest(
        WorkflowDefinitionRef definition, WorkflowState initialState, String idempotencyKey) {
    public WorkflowStartRequest {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(initialState, "initialState must not be null");
        idempotencyKey = Identifiers.requireValid(idempotencyKey, "idempotencyKey");
    }
}
