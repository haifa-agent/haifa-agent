package io.haifa.agent.sdk.api;

import io.haifa.agent.core.run.AgentRunId;
import java.util.Objects;

/**
 * Text steer input for one active Run.
 *
 * <p>The input reaches the model at the Run's next {@code BEFORE_ITERATION} safe point: never while a Tool is
 * executing or a model request is being built. The caller identity comes from the SDK caller provider, never from
 * this command. Retrying the same idempotency key with the same message is a duplicate, not a second input.
 */
public record RunInputCommand(AgentRunId runId, String idempotencyKey, String message) {
    public RunInputCommand {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey", 256);
        message = requireText(message, "message", 32_000);
    }

    private static String requireText(String value, String field, int limit) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > limit) throw new IllegalArgumentException(field + " is too long");
        return normalized;
    }
}
