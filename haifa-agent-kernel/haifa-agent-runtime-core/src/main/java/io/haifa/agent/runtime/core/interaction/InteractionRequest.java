package io.haifa.agent.runtime.core.interaction;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.InteractionRequestId;
import java.time.Instant;
import java.util.Objects;

public record InteractionRequest(
        InteractionRequestId id,
        AgentRunId runId,
        TenantRef tenant,
        PrincipalRef principal,
        String type,
        String prompt,
        boolean approval,
        InteractionTarget target,
        Instant createdAt,
        Instant expiresAt) {
    public InteractionRequest(
            InteractionRequestId id,
            AgentRunId runId,
            TenantRef tenant,
            PrincipalRef principal,
            String type,
            String prompt,
            boolean approval,
            Instant createdAt,
            Instant expiresAt) {
        this(
                id,
                runId,
                tenant,
                principal,
                type,
                prompt,
                approval,
                new GenericInteractionTarget(type),
                createdAt,
                expiresAt);
    }

    public InteractionRequest {
        id = Objects.requireNonNull(id, "id must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        type = requireText(type, "type");
        prompt = requireText(prompt, "prompt");
        target = Objects.requireNonNull(target, "target must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("expiresAt must be after createdAt");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
