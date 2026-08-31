package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.model.api.ImageInputProfile;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Safe product projection for Coding model selection surfaces. */
public record CodingModelOption(
        String id,
        String displayName,
        String providerId,
        String providerDisplayName,
        Set<String> capabilities,
        int contextWindow,
        int maxOutputTokens,
        CodingModelState state,
        String unavailableReason,
        CodingModelControls controls,
        CodingModelPreferences recommendedPreferences,
        Optional<ImageInputProfile> imageInput) {

    /** Compatibility constructor for old fixed catalogs; unknown profiles fail closed. */
    public CodingModelOption(
            String id,
            String displayName,
            String providerId,
            String providerDisplayName,
            Set<String> capabilities,
            int contextWindow) {
        this(
                id,
                displayName,
                providerId,
                providerDisplayName,
                capabilities,
                contextWindow,
                contextWindow,
                CodingModelState.unavailable(),
                "This model does not have a verified binding profile",
                CodingModelControls.unavailable(),
                CodingModelPreferences.recommended(),
                Optional.empty());
    }

    public CodingModelOption {
        id = CodingProductValues.requireText(id, "id", 128);
        displayName = CodingProductValues.requireText(displayName, "displayName", 128);
        providerId = CodingProductValues.requireText(providerId, "providerId", 128);
        providerDisplayName = CodingProductValues.requireText(providerDisplayName, "providerDisplayName", 128);
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
        if (contextWindow < 1) throw new IllegalArgumentException("contextWindow must be positive");
        if (maxOutputTokens < 1 || maxOutputTokens > contextWindow) {
            throw new IllegalArgumentException("maxOutputTokens must be positive and not exceed contextWindow");
        }
        state = Objects.requireNonNull(state, "state must not be null");
        unavailableReason = unavailableReason == null ? "" : unavailableReason.trim();
        if (state.bindingAvailability() == CodingModelState.BindingAvailability.AVAILABLE
                && !unavailableReason.isEmpty()) {
            throw new IllegalArgumentException("available model cannot have unavailable reason");
        }
        if (state.bindingAvailability() == CodingModelState.BindingAvailability.UNAVAILABLE
                && unavailableReason.isEmpty()) {
            throw new IllegalArgumentException("unavailable model requires a safe reason");
        }
        controls = Objects.requireNonNull(controls, "controls must not be null");
        recommendedPreferences =
                Objects.requireNonNull(recommendedPreferences, "recommendedPreferences must not be null");
        imageInput = Objects.requireNonNullElse(imageInput, Optional.empty());
    }
}
