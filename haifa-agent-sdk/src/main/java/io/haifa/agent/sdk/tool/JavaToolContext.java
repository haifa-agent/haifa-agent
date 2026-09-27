package io.haifa.agent.sdk.tool;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.tool.api.ToolCancellation;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Trusted invocation context supplied by the Runtime after validation and policy checks. */
public record JavaToolContext(
        AgentRunId runId,
        Optional<AgentSessionId> sessionId,
        ToolCallId toolCallId,
        TenantRef tenant,
        PrincipalRef principal,
        Instant deadline,
        Optional<String> idempotencyKey,
        ToolCancellation cancellation,
        Map<String, String> credentials) {
    public JavaToolContext {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        toolCallId = Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        deadline = Objects.requireNonNull(deadline, "deadline must not be null");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        cancellation = Objects.requireNonNull(cancellation, "cancellation must not be null");
        credentials = Map.copyOf(Objects.requireNonNull(credentials, "credentials must not be null"));
    }

    /**
     * Source-compatible legacy constructor. A caller cannot safely invent the Runtime-owned Tool Call identity,
     * so direct construction through this old shape fails closed instead of fabricating one.
     */
    public JavaToolContext(
            AgentRunId runId,
            TenantRef tenant,
            PrincipalRef principal,
            Instant deadline,
            Optional<String> idempotencyKey,
            ToolCancellation cancellation,
            Map<String, String> credentials) {
        this(
                runId,
                Optional.empty(),
                missingTrustedToolCallId(),
                tenant,
                principal,
                deadline,
                idempotencyKey,
                cancellation,
                credentials);
    }

    private static ToolCallId missingTrustedToolCallId() {
        throw new UnsupportedOperationException(
                "JavaToolContext must be supplied by the Runtime to include a trusted Tool Call identity");
    }
}
