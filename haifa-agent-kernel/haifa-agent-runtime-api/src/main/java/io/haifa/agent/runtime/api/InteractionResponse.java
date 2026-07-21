package io.haifa.agent.runtime.api;

import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Public interaction response envelope; operator identity is supplied by trusted caller context. */
public record InteractionResponse(
        InteractionResponseId responseId,
        InteractionRequestId requestId,
        AgentRunId runId,
        InteractionResponseType type,
        List<ContentPart> inputs,
        String idempotencyKey,
        Instant respondedAt) {
    public InteractionResponse {
        responseId = Objects.requireNonNull(responseId, "responseId must not be null");
        requestId = Objects.requireNonNull(requestId, "requestId must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs must not be null"));
        if (inputs.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("inputs must not contain null");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        respondedAt = Objects.requireNonNull(respondedAt, "respondedAt must not be null");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
