package io.haifa.agent.credential.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Trusted control-plane credential request. It deliberately has no synthetic Run identity. */
public record CredentialOperationRequest(
        CredentialOperation operation,
        TenantRef tenant,
        PrincipalRef principal,
        String targetBindingReference,
        CredentialRequirement requirement,
        List<CredentialBindingScope> scopeChain,
        Optional<String> explicitBindingId,
        Instant requestedAt,
        Instant expiresAt) {
    public CredentialOperationRequest {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principal, "principal");
        targetBindingReference = CredentialValues.text(targetBindingReference, "targetBindingReference");
        Objects.requireNonNull(requirement, "requirement");
        scopeChain = List.copyOf(Objects.requireNonNull(scopeChain, "scopeChain"));
        explicitBindingId = Objects.requireNonNull(explicitBindingId, "explicitBindingId");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(requestedAt)) {
            throw new IllegalArgumentException("expiresAt must be after requestedAt");
        }
    }
}
