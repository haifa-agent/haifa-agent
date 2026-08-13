package io.haifa.agent.model.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Provider-neutral parameters resolved by trusted product configuration for one new Run. */
public record EffectiveModelParameters(
        ModelDefinitionId bindingId,
        String profileVersion,
        String profileDigest,
        ModelReasoningPolicy reasoning,
        int maxOutputTokens) {
    public static final String PROFILE_VERSION_OPTION = "haifa.model.profile_version";
    public static final String PROFILE_DIGEST_OPTION = "haifa.model.profile_digest";
    public static final String MAX_OUTPUT_TOKENS_OPTION = "max_output_tokens";

    public EffectiveModelParameters {
        bindingId = Objects.requireNonNull(bindingId, "bindingId must not be null");
        profileVersion = ModelValues.text(profileVersion, "profileVersion");
        profileDigest = ModelValues.text(profileDigest, "profileDigest");
        if (!profileDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("profileDigest must be a lowercase SHA-256 digest");
        }
        reasoning = Objects.requireNonNull(reasoning, "reasoning must not be null");
        if (maxOutputTokens < 1) throw new IllegalArgumentException("maxOutputTokens must be positive");
    }

    public Map<String, Object> frozenOptions() {
        Map<String, Object> values = new LinkedHashMap<>(reasoning.frozenOptions());
        values.put(PROFILE_VERSION_OPTION, profileVersion);
        values.put(PROFILE_DIGEST_OPTION, profileDigest);
        values.put(MAX_OUTPUT_TOKENS_OPTION, maxOutputTokens);
        return Map.copyOf(values);
    }
}
