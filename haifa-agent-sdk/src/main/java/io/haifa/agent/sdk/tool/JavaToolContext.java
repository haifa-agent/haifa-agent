package io.haifa.agent.sdk.tool;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.tool.api.ToolCancellation;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Trusted invocation context supplied by the Runtime after validation and policy checks. */
public record JavaToolContext(
        AgentRunId runId,
        TenantRef tenant,
        PrincipalRef principal,
        Instant deadline,
        Optional<String> idempotencyKey,
        Optional<String> policyDecisionRef,
        ToolCancellation cancellation,
        List<CredentialLease> credentialLeases) {
    public JavaToolContext {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        deadline = Objects.requireNonNull(deadline, "deadline must not be null");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        policyDecisionRef = Objects.requireNonNull(policyDecisionRef, "policyDecisionRef must not be null");
        cancellation = Objects.requireNonNull(cancellation, "cancellation must not be null");
        credentialLeases = List.copyOf(Objects.requireNonNull(credentialLeases, "credentialLeases must not be null"));
    }
}
