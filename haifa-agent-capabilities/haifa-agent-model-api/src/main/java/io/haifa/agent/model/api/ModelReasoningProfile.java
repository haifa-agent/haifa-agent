package io.haifa.agent.model.api;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/** Typed reasoning limits and allowed controls for one exact model binding. */
public record ModelReasoningProfile(
        ModelReasoningBehavior behavior,
        Set<ModelReasoningMode> allowedModes,
        Set<ModelReasoningEffort> allowedEfforts,
        OptionalLong maximumTokens) {

    public ModelReasoningProfile {
        behavior = Objects.requireNonNull(behavior, "behavior must not be null");
        allowedModes = Set.copyOf(Objects.requireNonNull(allowedModes, "allowedModes must not be null"));
        allowedEfforts = Set.copyOf(Objects.requireNonNull(allowedEfforts, "allowedEfforts must not be null"));
        maximumTokens = Objects.requireNonNull(maximumTokens, "maximumTokens must not be null");
    }
}
