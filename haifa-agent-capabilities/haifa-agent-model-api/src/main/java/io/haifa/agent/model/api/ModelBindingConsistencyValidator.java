package io.haifa.agent.model.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Enforces strict bidirectional consistency between {@link ModelDefinition} (catalog identity and
 * display anchors) and {@link ModelBindingProfile} (authoritative execution contract).
 */
public final class ModelBindingConsistencyValidator {

    private ModelBindingConsistencyValidator() {}

    /**
     * Validates that a ModelDefinition strictly matches its authoritative ModelBindingProfile.
     *
     * @param definition the model definition
     * @param profile the authoritative model binding profile
     * @throws IllegalArgumentException if any consistency rule is violated
     */
    public static void validate(ModelDefinition definition, ModelBindingProfile profile) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(profile, "profile must not be null");

        // Rule 1: Binding ID match
        if (!definition.id().equals(profile.bindingId())) {
            throw new IllegalArgumentException("binding ID mismatch between definition ("
                    + definition.id()
                    + ") and profile ("
                    + profile.bindingId()
                    + ")");
        }

        // Rule 2: API Style match
        if (!definition.style().equals(profile.apiStyle())) {
            throw new IllegalArgumentException("API style mismatch between definition ("
                    + definition.style()
                    + ") and profile ("
                    + profile.apiStyle()
                    + ") for model: "
                    + definition.id());
        }

        // Rule 3: Capabilities match exactly (bidirectional set equality)
        if (!definition.capabilities().equals(profile.capabilities())) {
            throw new IllegalArgumentException("capabilities mismatch between definition ("
                    + definition.capabilities()
                    + ") and profile ("
                    + profile.capabilities()
                    + ") for model: "
                    + definition.id());
        }

        // Rule 4: Maximum output tokens match
        if (definition.maxOutputTokens() != profile.maximumOutputTokens()) {
            throw new IllegalArgumentException("maxOutputTokens mismatch between definition ("
                    + definition.maxOutputTokens()
                    + ") and profile ("
                    + profile.maximumOutputTokens()
                    + ") for model: "
                    + definition.id());
        }

        // Rule 5: Profile output tokens range validity
        if (profile.minimumOutputTokens() < 1 || profile.minimumOutputTokens() > profile.maximumOutputTokens()) {
            throw new IllegalArgumentException("invalid profile output token range (min="
                    + profile.minimumOutputTokens()
                    + ", max="
                    + profile.maximumOutputTokens()
                    + ") for model: "
                    + definition.id());
        }

        // Rule 6: Maximum output tokens does not exceed context window
        if (profile.maximumOutputTokens() > definition.contextWindow()) {
            throw new IllegalArgumentException("maximum output tokens ("
                    + profile.maximumOutputTokens()
                    + ") exceed context window ("
                    + definition.contextWindow()
                    + ") for model: "
                    + definition.id());
        }

        // Rule 7: Reasoning configuration and capability consistency
        boolean hasReasoning = profile.capabilities().contains(ModelCapability.REASONING);
        if (hasReasoning) {
            if (profile.reasoningBehavior() == ModelReasoningBehavior.NONE) {
                throw new IllegalArgumentException("model "
                        + definition.id()
                        + " declares REASONING capability but profile has NONE reasoning behavior");
            }
            if (profile.allowedReasoningEfforts().isEmpty()) {
                throw new IllegalArgumentException("model "
                        + definition.id()
                        + " declares REASONING capability but profile has empty allowed reasoning efforts");
            }
        } else {
            if (profile.reasoningBehavior() != ModelReasoningBehavior.NONE) {
                throw new IllegalArgumentException("non-reasoning model "
                        + definition.id()
                        + " cannot declare reasoning behavior: "
                        + profile.reasoningBehavior());
            }
            if (!profile.allowedReasoningModes().equals(Set.of(ModelReasoningMode.DISABLED))) {
                throw new IllegalArgumentException(
                        "non-reasoning model " + definition.id() + " must only allow DISABLED reasoning mode");
            }
            if (!profile.allowedReasoningEfforts().isEmpty()) {
                throw new IllegalArgumentException(
                        "non-reasoning model " + definition.id() + " must have empty allowed reasoning efforts");
            }
            if (profile.toolReasoningContinuationRequired()) {
                throw new IllegalArgumentException("non-reasoning model "
                        + definition.id()
                        + " cannot declare tool reasoning continuation required");
            }
        }
    }

    /**
     * Validates that a ModelProviderDefinition, its ModelDefinition, and corresponding profile are consistent.
     *
     * @param provider the parent model provider definition
     * @param definition the model definition
     * @param profile the authoritative model binding profile
     * @throws IllegalArgumentException if any consistency rule is violated
     */
    public static void validate(
            ModelProviderDefinition provider, ModelDefinition definition, ModelBindingProfile profile) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        if (!provider.id().equals(definition.providerId())) {
            throw new IllegalArgumentException("model "
                    + definition.id()
                    + " belongs to provider "
                    + definition.providerId()
                    + " but was validated against provider "
                    + provider.id());
        }
        validate(definition, profile);
    }

    /**
     * Validates all models in a provider against a profile lookup map.
     *
     * @param provider the provider definition containing models
     * @param profiles the profiles keyed by model definition id value
     * @throws IllegalArgumentException if any model fails validation or is missing a profile
     */
    public static void validateAll(ModelProviderDefinition provider, Map<String, ModelBindingProfile> profiles) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(profiles, "profiles must not be null");
        for (ModelDefinition model : provider.models()) {
            ModelBindingProfile profile = profiles.get(model.id().value());
            if (profile == null) {
                throw new IllegalArgumentException(
                        "missing authoritative profile for model: " + model.id().value());
            }
            validate(provider, model, profile);
        }
    }
}
