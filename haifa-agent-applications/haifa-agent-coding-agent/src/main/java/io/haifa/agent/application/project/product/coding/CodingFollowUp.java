package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record CodingFollowUp(
        String followUpId,
        AgentSessionId sessionId,
        AgentRunId boundRunId,
        String message,
        List<AssetRef> attachments,
        String idempotencyKeyDigest,
        String requestDigest,
        String dispatchKey,
        CodingFollowUpStatus status,
        long sequence,
        Optional<AgentRunId> dispatchedRunId,
        Instant createdAt,
        Instant updatedAt,
        long revision) {
    public CodingFollowUp {
        followUpId = CodingProductValues.requireText(followUpId, "followUpId", 256);
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        boundRunId = Objects.requireNonNull(boundRunId, "boundRunId must not be null");
        message = CodingProductValues.requireText(message, "message", 65_536);
        attachments = List.copyOf(Objects.requireNonNull(attachments, "attachments must not be null"));
        if (attachments.size() > 20 || attachments.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("attachments must contain at most 20 values");
        }
        idempotencyKeyDigest = CodingProductValues.requireText(idempotencyKeyDigest, "idempotencyKeyDigest", 128);
        requestDigest = CodingProductValues.requireText(requestDigest, "requestDigest", 128);
        dispatchKey = CodingProductValues.requireText(dispatchKey, "dispatchKey", 256);
        status = Objects.requireNonNull(status, "status must not be null");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        dispatchedRunId = Objects.requireNonNull(dispatchedRunId, "dispatchedRunId must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt precedes createdAt");
        if ((status == CodingFollowUpStatus.DISPATCHED) != dispatchedRunId.isPresent()) {
            throw new IllegalArgumentException("only dispatched follow-up requires a Run");
        }
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
    }
}
