package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.run.AgentRunId;
import java.util.Objects;
import java.util.Optional;

/** Durable binding between a start idempotency key, its canonical request, and the accepted Run. */
public record RunStartIdempotencyBinding(
        String callerScope, String operation, String idempotencyKey, Optional<String> requestDigest, AgentRunId runId) {

    public RunStartIdempotencyBinding {
        callerScope = requireText(callerScope, "callerScope");
        operation = requireText(operation, "operation");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        requestDigest = Objects.requireNonNull(requestDigest, "requestDigest must not be null")
                .map(value -> requireText(value, "requestDigest"));
        runId = Objects.requireNonNull(runId, "runId must not be null");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
