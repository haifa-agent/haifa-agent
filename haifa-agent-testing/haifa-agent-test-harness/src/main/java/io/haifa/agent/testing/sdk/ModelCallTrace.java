package io.haifa.agent.testing.sdk;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.ModelMessageRole;
import java.util.List;
import java.util.Objects;

/** Content-redacted trajectory for one scripted model call. */
public record ModelCallTrace(
        AgentRunId runId,
        int iteration,
        int attempt,
        String modelId,
        List<ModelMessageRole> messageRoles,
        List<String> messageDigests,
        List<String> toolNames) {
    public ModelCallTrace {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        if (iteration < 1 || attempt < 1) throw new IllegalArgumentException("iteration and attempt must be positive");
        modelId = Objects.requireNonNull(modelId, "modelId must not be null");
        messageRoles = List.copyOf(messageRoles);
        messageDigests = List.copyOf(messageDigests);
        toolNames = List.copyOf(toolNames);
    }
}
