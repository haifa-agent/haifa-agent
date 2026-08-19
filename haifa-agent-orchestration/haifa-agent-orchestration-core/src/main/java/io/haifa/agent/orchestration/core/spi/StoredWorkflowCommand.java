package io.haifa.agent.orchestration.core.spi;

import io.haifa.agent.orchestration.api.WorkflowRunId;
import io.haifa.agent.orchestration.api.WorkflowRunSnapshot;
import java.util.Objects;

/** Durable idempotency receipt, including the exact result returned by the command. */
public record StoredWorkflowCommand(
        String operation,
        String scope,
        String idempotencyKeyDigest,
        String requestDigest,
        WorkflowRunId runId,
        WorkflowRunSnapshot result) {
    public StoredWorkflowCommand {
        operation = required(operation, "operation");
        scope = required(scope, "scope");
        idempotencyKeyDigest = required(idempotencyKeyDigest, "idempotencyKeyDigest");
        requestDigest = required(requestDigest, "requestDigest");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(result, "result must not be null");
    }

    private static String required(String value, String name) {
        String normalized =
                Objects.requireNonNull(value, name + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
