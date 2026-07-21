package io.haifa.agent.runtime.api.checkpoint;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record CapabilityCheckpointCaptureContext(
        AgentRunId runId,
        AgentSessionId sessionId,
        TenantRef tenant,
        PrincipalRef principal,
        Set<String> enabledCapabilities,
        String checkpointRef,
        Instant capturedAt) {
    public CapabilityCheckpointCaptureContext {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        enabledCapabilities =
                Set.copyOf(Objects.requireNonNull(enabledCapabilities, "enabledCapabilities must not be null"));
        checkpointRef = Objects.requireNonNull(checkpointRef, "checkpointRef must not be null")
                .trim();
        if (checkpointRef.isEmpty()) throw new IllegalArgumentException("checkpointRef must not be blank");
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt must not be null");
    }
}
