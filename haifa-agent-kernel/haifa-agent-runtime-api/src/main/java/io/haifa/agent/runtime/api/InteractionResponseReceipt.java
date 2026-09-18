package io.haifa.agent.runtime.api;

import io.haifa.agent.core.run.AgentRunId;
import java.util.Objects;

/** Durable response acknowledgement, distinct from final run or business completion. */
public record InteractionResponseReceipt(
        InteractionResponseId responseId,
        InteractionRequestId requestId,
        AgentRunId runId,
        InteractionResponseReceiptStatus status,
        InteractionState interactionState,
        long revision,
        long runVersion) {
    public InteractionResponseReceipt {
        responseId = Objects.requireNonNull(responseId, "responseId must not be null");
        requestId = Objects.requireNonNull(requestId, "requestId must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        interactionState = Objects.requireNonNull(interactionState, "interactionState must not be null");
        if (revision < 0 || runVersion < 0) throw new IllegalArgumentException("versions must not be negative");
    }
}
