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

        // Rule 4: Context window mirror matches the authoritative execution limits.
        if (definition.contextWindow() != profile.contextWindowTokens()) {
            throw new IllegalArgumentException("contextWindow mismatch between definition ("
                    + definition.contextWindow()
                    + ") and profile ("
                    + profile.contextWindowTokens()
                    + ") for model: "
                    + definition.id());
        }

        // Rule 5: Maximum output tokens match
        if (definition.maxOutputTokens() != profile.maximumOutputTokens()) {
            throw new IllegalArgumentException("maxOutputTokens mismatch between definition ("
                    + definition.maxOutputTokens()
                    + ") and profile ("
                    + profile.maximumOutputTokens()
                    + ") for model: "
                    + definition.id());
        }

        // Rule 6: Profile output tokens range validity
        if (profile.minimumOutputTokens() < 1 || profile.minimumOutputTokens() > profile.maximumOutputTokens()) {
            throw new IllegalArgumentException("invalid profile output token range (min="
                    + profile.minimumOutputTokens()
                    + ", max="
                    + profile.maximumOutputTokens()
                    + ") for model: "
                    + definition.id());
        }

        // Rule 7: Maximum output tokens does not exceed the profile context window.
        if (profile.maximumOutputTokens() > profile.contextWindowTokens()) {
            throw new IllegalArgumentException("maximum output tokens ("
                    + profile.maximumOutputTokens()
                    + ") exceed context window ("
                    + profile.contextWindowTokens()
                    + ") for model: "
                    + definition.id());
        }

        // Rule 8: Reasoning streaming requires reasoning capability.
        if (profile.streaming().reasoningStreaming() && !profile.capabilities().contains(ModelCapability.REASONING)) {
            throw new IllegalArgumentException(
                    "non-reasoning model " + definition.id() + " cannot declare reasoning streaming");
        }

        // Rule 9: Reasoning configuration and capability consistency
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

        // Rule 10: Multimodal / IO Profile bidirectional consistency
        if (profile.capabilities().contains(ModelCapability.IMAGE_INPUT)) {
            throw new IllegalArgumentException(
                    "deprecated IMAGE_INPUT capability is not allowed in binding profile for model: "
                            + definition.id());
        }

        boolean hasImageUpload = profile.capabilities().contains(ModelCapability.IMAGE_UPLOAD_INPUT);
        boolean hasImageUrl = profile.capabilities().contains(ModelCapability.IMAGE_URL_INPUT);
        boolean hasImageCapability = hasImageUpload || hasImageUrl;

        if (profile.ioProfile().imageInput().isPresent()) {
            ImageInputProfile img = profile.ioProfile().imageInput().get();
            if (!profile.ioProfile().inputModalities().contains(ModelInputModality.IMAGE)) {
                throw new IllegalArgumentException(
                        "imageInput is present but inputModalities does not contain IMAGE for model: "
                                + definition.id());
            }
            if (img.allowedSources().contains(ModelImageSource.UPLOAD) && !hasImageUpload) {
                throw new IllegalArgumentException("profile allows UPLOAD image source but model " + definition.id()
                        + " does not declare IMAGE_UPLOAD_INPUT capability");
            }
            if (img.allowedSources().contains(ModelImageSource.URL) && !hasImageUrl) {
                throw new IllegalArgumentException("profile allows URL image source but model " + definition.id()
                        + " does not declare IMAGE_URL_INPUT capability");
            }
            if (hasImageUpload && !img.allowedSources().contains(ModelImageSource.UPLOAD)) {
                throw new IllegalArgumentException("model " + definition.id()
                        + " declares IMAGE_UPLOAD_INPUT but profile imageInput does not allow UPLOAD source");
            }
            if (hasImageUrl && !img.allowedSources().contains(ModelImageSource.URL)) {
                throw new IllegalArgumentException("model " + definition.id()
                        + " declares IMAGE_URL_INPUT but profile imageInput does not allow URL source");
            }
        } else {
            if (profile.ioProfile().inputModalities().contains(ModelInputModality.IMAGE)) {
                throw new IllegalArgumentException(
                        "inputModalities contains IMAGE but imageInput is missing for model: " + definition.id());
            }
            if (hasImageCapability) {
                throw new IllegalArgumentException(
                        "model " + definition.id() + " declares image capabilities but profile imageInput is missing");
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
        if (provider.nativeStreaming() != profile.streaming().nativeStreaming()) {
            throw new IllegalArgumentException("nativeStreaming mismatch between provider ("
                    + provider.nativeStreaming()
                    + ") and profile ("
                    + profile.streaming().nativeStreaming()
                    + ") for model: "
                    + definition.id());
        }
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
