package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.application.project.product.coding.delivery.CodingDeliveryIntent;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record CodingCommandBinding(
        String callerScopeDigest,
        String operation,
        String idempotencyKeyDigest,
        String requestDigest,
        String dispatchKey,
        AgentSessionId sessionId,
        ProjectId projectId,
        String message,
        List<AssetRef> attachments,
        CodingDeliveryIntent deliveryIntent,
        Optional<AgentRunId> runId,
        Instant createdAt) {
    public CodingCommandBinding {
        callerScopeDigest = CodingProductValues.requireText(callerScopeDigest, "callerScopeDigest", 128);
        operation = CodingProductValues.requireText(operation, "operation", 64);
        idempotencyKeyDigest = CodingProductValues.requireText(idempotencyKeyDigest, "idempotencyKeyDigest", 128);
        requestDigest = CodingProductValues.requireText(requestDigest, "requestDigest", 128);
        dispatchKey = CodingProductValues.requireText(dispatchKey, "dispatchKey", 256);
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        message = CodingProductValues.requireText(message, "message", 65_536);
        attachments = List.copyOf(Objects.requireNonNull(attachments, "attachments must not be null"));
        if (attachments.size() > 20 || attachments.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("attachments must contain at most 20 values");
        }
        deliveryIntent = Objects.requireNonNull(deliveryIntent, "deliveryIntent must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public CodingCommandBinding(
            String callerScopeDigest,
            String operation,
            String idempotencyKeyDigest,
            String requestDigest,
            String dispatchKey,
            AgentSessionId sessionId,
            ProjectId projectId,
            String message,
            List<AssetRef> attachments,
            Optional<AgentRunId> runId,
            Instant createdAt) {
        this(
                callerScopeDigest,
                operation,
                idempotencyKeyDigest,
                requestDigest,
                dispatchKey,
                sessionId,
                projectId,
                message,
                attachments,
                CodingDeliveryIntent.WORKTREE_ONLY,
                runId,
                createdAt);
    }
}
