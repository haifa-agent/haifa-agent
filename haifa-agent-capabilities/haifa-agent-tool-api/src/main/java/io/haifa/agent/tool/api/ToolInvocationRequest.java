package io.haifa.agent.tool.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import java.time.Instant;
import java.util.Map;
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
        Map<String, String> credentials,
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
        credentials = Map.copyOf(Objects.requireNonNull(credentials, "credentials"));
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
            Map<String, String> credentials) {
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
                credentials,
                ToolInvocationObserver.noop());
    }
}
