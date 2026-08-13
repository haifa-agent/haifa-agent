package io.haifa.agent.model.api;

import java.util.Objects;

/** Exact, stale-safe inputs to the common model parameter resolver. */
public record ModelParameterResolutionRequest(
        ModelDefinitionId bindingId,
        String profileVersion,
        String profileDigest,
        ModelReasoningPolicy reasoning,
        int maxOutputTokens) {
    public ModelParameterResolutionRequest {
        bindingId = Objects.requireNonNull(bindingId, "bindingId must not be null");
        profileVersion = ModelValues.text(profileVersion, "profileVersion");
        profileDigest = ModelValues.text(profileDigest, "profileDigest");
        reasoning = Objects.requireNonNull(reasoning, "reasoning must not be null");
        if (maxOutputTokens < 1) throw new IllegalArgumentException("maxOutputTokens must be positive");
    }
}
