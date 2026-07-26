package io.haifa.agent.runtime.api;

import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Revision-aware interaction response; responder identity comes from trusted caller context. */
public record InteractionResponseSubmission(
        InteractionResponseId responseId,
        InteractionRequestId requestId,
        AgentRunId runId,
        long expectedRevision,
        InteractionAction action,
        List<ContentPart> inputs,
        String idempotencyKey,
        Instant respondedAt) {
    public InteractionResponseSubmission {
        responseId = Objects.requireNonNull(responseId, "responseId must not be null");
        requestId = Objects.requireNonNull(requestId, "requestId must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must not be negative");
        action = Objects.requireNonNull(action, "action must not be null");
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs must not be null"));
        if (inputs.size() > 100 || inputs.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("inputs must contain at most 100 non-null parts");
        }
        if (inputs.stream()
                .anyMatch(part -> part instanceof io.haifa.agent.core.content.ToolCallPart
                        || part instanceof io.haifa.agent.core.content.ToolResultPart)) {
            throw new IllegalArgumentException("interaction input must not contain tool protocol parts");
        }
        idempotencyKey = InteractionOption.requireText(idempotencyKey, "idempotencyKey", 256);
        respondedAt = Objects.requireNonNull(respondedAt, "respondedAt must not be null");
    }
}
