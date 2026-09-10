package io.haifa.agent.credential.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record CredentialRequest(
        TenantRef tenant,
        PrincipalRef principal,
        AgentRunId runId,
        String toolCoordinate,
        CredentialRequirement requirement,
        List<CredentialBindingScope> scopeChain,
        Instant requestedAt,
        Instant expiresAt) {
    public CredentialRequest {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(runId, "runId");
        toolCoordinate = CredentialValues.text(toolCoordinate, "toolCoordinate");
        Objects.requireNonNull(requirement, "requirement");
        scopeChain = List.copyOf(Objects.requireNonNull(scopeChain, "scopeChain"));
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(requestedAt)) {
            throw new IllegalArgumentException("expiresAt must be after requestedAt");
        }
    }
}
