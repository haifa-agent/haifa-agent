package io.haifa.agent.credential.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.Objects;

/** Secret-free credential usage metadata suitable for an audit adapter. */
public record CredentialUsageAudit(
        CredentialReference reference,
        CredentialDefinitionId definitionId,
        TenantRef tenant,
        PrincipalRef principal,
        AgentRunId runId,
        String toolCoordinate,
        String purpose,
        Instant occurredAt,
        Instant leaseExpiresAt,
        CredentialUsagePhase phase) {
    public CredentialUsageAudit {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(runId, "runId");
        toolCoordinate = CredentialValues.text(toolCoordinate, "toolCoordinate");
        purpose = CredentialValues.text(purpose, "purpose");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        Objects.requireNonNull(phase, "phase");
    }
}
