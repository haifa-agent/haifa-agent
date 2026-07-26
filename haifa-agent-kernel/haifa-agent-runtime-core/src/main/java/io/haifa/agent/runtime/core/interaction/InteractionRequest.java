package io.haifa.agent.runtime.core.interaction;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.policy.api.ApprovalRequestContext;
import io.haifa.agent.runtime.api.InteractionRequestId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record InteractionRequest(
        InteractionRequestId id,
        AgentRunId runId,
        TenantRef tenant,
        PrincipalRef requester,
        String type,
        String prompt,
        boolean approval,
        InteractionTarget target,
        Instant createdAt,
        Instant expiresAt,
        Optional<ApprovalRequestContext> approvalContext) {
    public InteractionRequest(
            InteractionRequestId id,
            AgentRunId runId,
            TenantRef tenant,
            PrincipalRef requester,
            String type,
            String prompt,
            boolean approval,
            InteractionTarget target,
            Instant createdAt,
            Instant expiresAt) {
        this(id, runId, tenant, requester, type, prompt, approval, target, createdAt, expiresAt, Optional.empty());
    }

    public InteractionRequest(
            InteractionRequestId id,
            AgentRunId runId,
            TenantRef tenant,
            PrincipalRef requester,
            String type,
            String prompt,
            boolean approval,
            Instant createdAt,
            Instant expiresAt) {
        this(
                id,
                runId,
                tenant,
                requester,
                type,
                prompt,
                approval,
                new GenericInteractionTarget(type),
                createdAt,
                expiresAt,
                Optional.empty());
    }

    public InteractionRequest {
        id = Objects.requireNonNull(id, "id must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        requester = Objects.requireNonNull(requester, "requester must not be null");
        type = requireText(type, "type");
        prompt = requireText(prompt, "prompt");
        target = Objects.requireNonNull(target, "target must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        approvalContext = Objects.requireNonNull(approvalContext, "approvalContext must not be null");
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("expiresAt must be after createdAt");
        if (!approval && approvalContext.isPresent()) {
            throw new IllegalArgumentException("only approval interactions may carry approval context");
        }
        if (approvalContext.isPresent()) {
            ApprovalRequestContext context = approvalContext.orElseThrow();
            if (!context.requester().tenant().equals(tenant)
                    || !context.requester().principal().equals(requester)
                    || !context.createdAt().equals(createdAt)
                    || !context.expiresAt().equals(expiresAt)) {
                throw new IllegalArgumentException("approval context does not match interaction request");
            }
        }
    }

    /** @deprecated Use requester() to distinguish it from the trusted response caller. */
    @Deprecated(forRemoval = true)
    public PrincipalRef principal() {
        return requester;
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
