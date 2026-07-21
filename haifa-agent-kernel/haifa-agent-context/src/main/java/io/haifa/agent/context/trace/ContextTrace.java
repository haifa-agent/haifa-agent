package io.haifa.agent.context.trace;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.List;
import java.util.Objects;

public record ContextTrace(
        AgentRunId runId,
        AgentSessionId sessionId,
        int iteration,
        String modelConfigurationDigest,
        String estimatorVersion,
        String selectionPolicyVersion,
        String compressionPolicyVersion,
        String compressorVersion,
        int forcedRebuildAttempt,
        long promptTokens,
        long toolTokens,
        long selectedItemTokens,
        List<ContextTraceItem> items) {
    public ContextTrace {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (iteration < 1) throw new IllegalArgumentException("iteration must be positive");
        modelConfigurationDigest = Objects.requireNonNull(modelConfigurationDigest);
        estimatorVersion = Objects.requireNonNull(estimatorVersion);
        selectionPolicyVersion = Objects.requireNonNull(selectionPolicyVersion);
        compressionPolicyVersion = Objects.requireNonNull(compressionPolicyVersion);
        compressorVersion = Objects.requireNonNull(compressorVersion);
        if (forcedRebuildAttempt < 0 || forcedRebuildAttempt > 1) {
            throw new IllegalArgumentException("forcedRebuildAttempt must be zero or one");
        }
        if (promptTokens < 0 || toolTokens < 0 || selectedItemTokens < 0) {
            throw new IllegalArgumentException("trace token values must not be negative");
        }
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }
}
