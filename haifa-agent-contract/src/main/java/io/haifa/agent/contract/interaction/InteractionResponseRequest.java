package io.haifa.agent.contract.interaction;

import io.haifa.agent.contract.common.ContentPartDto;
import io.haifa.agent.contract.common.CorrelationId;
import io.haifa.agent.contract.common.IdempotencyKey;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record InteractionResponseRequest(
        String responseId,
        String requestId,
        String runId,
        long expectedRevision,
        String action,
        List<ContentPartDto> inputs,
        IdempotencyKey idempotencyKey,
        Instant respondedAt) {
    public InteractionResponseRequest {
        responseId = CorrelationId.requireText(responseId, "responseId", 256);
        requestId = CorrelationId.requireText(requestId, "requestId", 256);
        runId = CorrelationId.requireText(runId, "runId", 256);
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must not be negative");
        action = CorrelationId.requireText(action, "action", 64);
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs must not be null"));
        if (inputs.size() > 100 || inputs.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("inputs must contain at most 100 non-null parts");
        }
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        respondedAt = Objects.requireNonNull(respondedAt, "respondedAt must not be null");
    }
}
