package io.haifa.agent.runtime.core.model;

import io.haifa.agent.runtime.core.decision.AgentDecision;
import java.util.Objects;

public record ModelResponse(AgentDecision decision, long inputTokens, long outputTokens, long costMinorUnits) {
    public ModelResponse {
        decision = Objects.requireNonNull(decision, "decision must not be null");
        if (inputTokens < 0 || outputTokens < 0 || costMinorUnits < 0) {
            throw new IllegalArgumentException("model usage must not be negative");
        }
    }
}
