package io.haifa.agent.runtime.core.decision;

import java.util.List;
import java.util.Objects;

public record ToolCallDecision(List<ToolRequest> requests) implements AgentDecision {
    public ToolCallDecision {
        requests = List.copyOf(Objects.requireNonNull(requests, "requests must not be null"));
        if (requests.isEmpty()) throw new IllegalArgumentException("tool requests must not be empty");
    }
}
