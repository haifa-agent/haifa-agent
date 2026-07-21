package io.haifa.agent.runtime.core.decision;

import java.util.Objects;

public record ContinueDecision(String message) implements AgentDecision {
    public ContinueDecision {
        message = Objects.requireNonNull(message, "message must not be null").trim();
        if (message.isEmpty()) throw new IllegalArgumentException("message must not be blank");
    }
}
