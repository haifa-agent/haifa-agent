package io.haifa.agent.sdk.memory;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.sdk.internal.CanonicalSdkDigest;
import java.util.Objects;

/**
 * Trusted host reference to one explicitly authorized action in a persisted USER turn.
 * Assign the index from the original instruction, never from a model call, attempt or retry.
 */
public record UserMemoryInstruction(AgentSessionId sessionId, AgentRunId runId, String messageId, int actionIndex) {
    public UserMemoryInstruction {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(messageId);
        if (messageId.isBlank() || messageId.length() > 256 || actionIndex < 0) {
            throw new IllegalArgumentException("Invalid user instruction reference");
        }
    }

    String key() {
        return CanonicalSdkDigest.sha256(
                "memory-user-action-v1", sessionId.value(), runId.value(), messageId, Integer.toString(actionIndex));
    }
}
