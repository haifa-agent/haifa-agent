package io.haifa.agent.runtime.core.model.continuation;

import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.Set;

public record ModelContinuationRecord(
        ModelContinuationRef reference,
        AgentMessageId assistantMessageId,
        AgentRunId runId,
        AgentSessionId sessionId,
        String modelCallId,
        String providerId,
        String modelId,
        String configurationDigest,
        Set<String> toolCorrelationIds,
        ProtectedModelReasoning protectedReasoning,
        Instant createdAt) {
    @Override
    public String toString() {
        return "ModelContinuationRecord[reference=" + reference + ", assistantMessageId=" + assistantMessageId
                + ", providerId=" + providerId + ", modelId=" + modelId + ", correlations="
                + toolCorrelationIds.size() + "]";
    }
}
