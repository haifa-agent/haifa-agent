package io.haifa.agent.runtime.core.model;

import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.api.SensitiveModelReasoning;
import io.haifa.agent.runtime.core.decision.AgentDecision;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Runtime outcome of one Model API invocation; not a compatibility model protocol. */
public record ModelInvocationResult(
        AgentDecision decision,
        long inputTokens,
        long outputTokens,
        boolean costKnown,
        long costMinorUnits,
        Map<String, Object> metadata,
        String modelCallId,
        ResolvedModelSnapshot model,
        Optional<SensitiveModelReasoning> reasoning) {
    public ModelInvocationResult {
        decision = Objects.requireNonNull(decision, "decision must not be null");
        if (inputTokens < 0 || outputTokens < 0 || costMinorUnits < 0) {
            throw new IllegalArgumentException("model usage must not be negative");
        }
        if (!costKnown && costMinorUnits != 0) {
            throw new IllegalArgumentException("unknown model cost must not have a value");
        }
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
        modelCallId = Objects.requireNonNull(modelCallId, "modelCallId must not be null");
        model = Objects.requireNonNull(model, "model must not be null");
        reasoning = Objects.requireNonNull(reasoning, "reasoning must not be null");
    }
}
