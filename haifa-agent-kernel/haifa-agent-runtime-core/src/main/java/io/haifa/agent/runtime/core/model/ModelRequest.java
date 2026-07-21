package io.haifa.agent.runtime.core.model;

import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.run.AgentRunId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ModelRequest(
        AgentRunId runId, int iteration, List<AgentMessage> messages, Map<String, Object> attributes) {
    public ModelRequest {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        if (iteration < 1) throw new IllegalArgumentException("iteration must be positive");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
    }
}
