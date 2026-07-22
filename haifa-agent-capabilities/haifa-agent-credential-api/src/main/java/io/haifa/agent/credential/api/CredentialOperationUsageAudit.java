package io.haifa.agent.credential.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.time.Instant;
import java.util.Objects;

/** Secret-free audit event for control-plane credential usage. */
public record CredentialOperationUsageAudit(
        CredentialReference reference,
        CredentialDefinitionId definitionId,
        TenantRef tenant,
        PrincipalRef principal,
        CredentialOperation operation,
        String targetBindingReference,
        String purpose,
        Instant occurredAt,
        Instant leaseExpiresAt,
        CredentialUsagePhase phase) {
    public CredentialOperationUsageAudit {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(operation, "operation");
        targetBindingReference = CredentialValues.text(targetBindingReference, "targetBindingReference");
        purpose = CredentialValues.text(purpose, "purpose");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        Objects.requireNonNull(phase, "phase");
    }
}
