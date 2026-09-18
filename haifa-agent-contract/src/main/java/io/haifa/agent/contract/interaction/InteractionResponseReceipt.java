package io.haifa.agent.contract.interaction;

import io.haifa.agent.contract.common.ApiVersion;
import io.haifa.agent.contract.common.CorrelationId;
import java.util.Objects;

public record InteractionResponseReceipt(
        ApiVersion apiVersion,
        String responseId,
        String requestId,
        String runId,
        String status,
        String interactionState,
        long revision,
        long runVersion) {
    public InteractionResponseReceipt {
        apiVersion = Objects.requireNonNull(apiVersion, "apiVersion must not be null");
        responseId = CorrelationId.requireText(responseId, "responseId", 256);
        requestId = CorrelationId.requireText(requestId, "requestId", 256);
        runId = CorrelationId.requireText(runId, "runId", 256);
        status = CorrelationId.requireText(status, "status", 64);
        interactionState = CorrelationId.requireText(interactionState, "interactionState", 64);
        if (revision < 0 || runVersion < 0) throw new IllegalArgumentException("versions must not be negative");
    }
}
