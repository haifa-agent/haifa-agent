package io.haifa.agent.runtime.core.model.continuation;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.SensitiveModelReasoning;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record ModelContinuationDraft(
        ModelContinuationRef reference,
        AgentRunId runId,
        AgentSessionId sessionId,
        String modelCallId,
        String providerId,
        String modelId,
        String configurationDigest,
        Set<String> toolCorrelationIds,
        SensitiveModelReasoning reasoning,
        Instant createdAt) {
    public ModelContinuationDraft {
        reference = Objects.requireNonNull(reference);
        runId = Objects.requireNonNull(runId);
        sessionId = Objects.requireNonNull(sessionId);
        modelCallId = Objects.requireNonNull(modelCallId);
        providerId = Objects.requireNonNull(providerId);
        modelId = Objects.requireNonNull(modelId);
        configurationDigest = Objects.requireNonNull(configurationDigest);
        toolCorrelationIds = Set.copyOf(toolCorrelationIds);
        reasoning = Objects.requireNonNull(reasoning);
        createdAt = Objects.requireNonNull(createdAt);
        if (!reference.digest().equals(reasoning.digest()) || reference.byteLength() != reasoning.byteLength()) {
            throw new IllegalArgumentException("continuation reference does not describe reasoning payload");
        }
    }
}
