package io.haifa.agent.context.trace;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.List;
import java.util.Objects;

/** Redacted, per-build context evidence shared by Runtime Trace and SDK diagnostics. */
public record ContextReport(
        AgentRunId runId,
        AgentSessionId sessionId,
        int iteration,
        String modelConfigurationDigest,
        String estimatorVersion,
        String selectionPolicyVersion,
        String compressionPolicyVersion,
        String compressorVersion,
        int forcedRebuildAttempt,
        long estimatedTokens,
        List<ContextReportComponent> components) {
    public ContextReport {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (iteration < 1) throw new IllegalArgumentException("iteration must be positive");
        modelConfigurationDigest = required(modelConfigurationDigest, "modelConfigurationDigest");
        estimatorVersion = required(estimatorVersion, "estimatorVersion");
        selectionPolicyVersion = required(selectionPolicyVersion, "selectionPolicyVersion");
        compressionPolicyVersion = required(compressionPolicyVersion, "compressionPolicyVersion");
        compressorVersion = required(compressorVersion, "compressorVersion");
        if (forcedRebuildAttempt < 0 || forcedRebuildAttempt > 1) {
            throw new IllegalArgumentException("forcedRebuildAttempt must be zero or one");
        }
        if (estimatedTokens < 0) throw new IllegalArgumentException("estimatedTokens must not be negative");
        components = List.copyOf(Objects.requireNonNull(components, "components must not be null"));
    }

    private static String required(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
