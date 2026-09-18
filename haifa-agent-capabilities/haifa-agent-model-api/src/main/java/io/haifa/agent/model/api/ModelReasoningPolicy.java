package io.haifa.agent.model.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Typed reasoning configuration whose normalized values can be frozen into snapshot option maps. */
public record ModelReasoningPolicy(
        ModelReasoningMode mode, Optional<ModelReasoningEffort> effort, OptionalLong tokenBudget) {
    public ModelReasoningPolicy {
        mode = Objects.requireNonNull(mode, "mode must not be null");
        effort = Objects.requireNonNull(effort, "effort must not be null");
        tokenBudget = Objects.requireNonNull(tokenBudget, "tokenBudget must not be null");
        if (tokenBudget.isPresent() && tokenBudget.getAsLong() < 1) {
            throw new IllegalArgumentException("reasoning token budget must be positive");
        }
        if (mode == ModelReasoningMode.DISABLED && (effort.isPresent() || tokenBudget.isPresent())) {
            throw new IllegalArgumentException("disabled reasoning cannot have effort or token budget");
        }
    }

    public static ModelReasoningPolicy disabled() {
        return new ModelReasoningPolicy(ModelReasoningMode.DISABLED, Optional.empty(), OptionalLong.empty());
    }

    public static ModelReasoningPolicy enabled(ModelReasoningEffort effort) {
        return new ModelReasoningPolicy(ModelReasoningMode.ENABLED, Optional.of(effort), OptionalLong.empty());
    }

    public Map<String, Object> frozenOptions() {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("thinking", mode.name().toLowerCase(java.util.Locale.ROOT));
        effort.ifPresent(value -> result.put("reasoning_effort", value.name().toLowerCase(java.util.Locale.ROOT)));
        tokenBudget.ifPresent(value -> result.put("reasoning_token_budget", value));
        return Map.copyOf(result);
    }
}
