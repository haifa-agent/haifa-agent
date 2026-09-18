package io.haifa.agent.tool.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import java.util.Objects;
import java.util.Optional;

/** Immutable facts available to a provider's read-only reconciliation path. */
public record ToolReconciliationRequest(
        FrozenToolBinding binding,
        ToolCallId toolCallId,
        AgentRunId runId,
        TenantRef tenant,
        PrincipalRef principal,
        ToolArguments arguments,
        String idempotencyKey,
        Optional<ToolDispatchEvidence> dispatchEvidence,
        Optional<ToolResult> observedResult) {
    public ToolReconciliationRequest {
        binding = Objects.requireNonNull(binding, "binding must not be null");
        toolCallId = Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        arguments = Objects.requireNonNull(arguments, "arguments must not be null");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null")
                .trim();
        if (idempotencyKey.isEmpty() || idempotencyKey.length() > 512) {
            throw new IllegalArgumentException("idempotencyKey must contain bounded text");
        }
        dispatchEvidence = Objects.requireNonNull(dispatchEvidence, "dispatchEvidence must not be null");
        observedResult = Objects.requireNonNull(observedResult, "observedResult must not be null");
    }
}
