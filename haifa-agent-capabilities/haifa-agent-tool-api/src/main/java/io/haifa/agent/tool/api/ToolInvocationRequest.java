package io.haifa.agent.tool.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.credential.api.CredentialLease;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ToolInvocationRequest(
        FrozenToolBinding binding,
        ToolCallId toolCallId,
        AgentRunId runId,
        TenantRef tenant,
        PrincipalRef principal,
        ToolArguments arguments,
        Instant deadline,
        Optional<String> idempotencyKey,
        ToolCancellation cancellation,
        List<CredentialLease> credentialLeases,
        ToolInvocationObserver observer) {
    public ToolInvocationRequest {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(deadline, "deadline");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(cancellation, "cancellation");
        credentialLeases = List.copyOf(Objects.requireNonNull(credentialLeases, "credentialLeases"));
        Objects.requireNonNull(observer, "observer");
    }

    public ToolInvocationRequest(
            FrozenToolBinding binding,
            ToolCallId toolCallId,
            AgentRunId runId,
            TenantRef tenant,
            PrincipalRef principal,
            ToolArguments arguments,
            Instant deadline,
            Optional<String> idempotencyKey,
            ToolCancellation cancellation,
            List<CredentialLease> credentialLeases) {
        this(
                binding,
                toolCallId,
                runId,
                tenant,
                principal,
                arguments,
                deadline,
                idempotencyKey,
                cancellation,
                credentialLeases,
                ToolInvocationObserver.noop());
    }
}
