package io.haifa.agent.personalassistant.application;

import java.util.Objects;
import java.util.Set;

public record PersonalModelOption(
        String id,
        String modelGroupId,
        String modelDisplayName,
        String displayName,
        String providerId,
        String providerDisplayName,
        String apiStyle,
        String apiStyleDisplayName,
        String availability,
        String unavailableReason,
        Set<String> capabilities,
        int contextWindow,
        int maxOutputTokens,
        String preferenceSchemaVersion,
        String profileVersion,
        String profileDigest,
        PersonalModelControls controls,
        PersonalModelPreferences recommendedPreferences) {
    public PersonalModelOption {
        id = text(id, "id");
        modelGroupId = text(modelGroupId, "modelGroupId");
        modelDisplayName = text(modelDisplayName, "modelDisplayName");
        displayName = text(displayName, "displayName");
        providerId = text(providerId, "providerId");
        providerDisplayName = text(providerDisplayName, "providerDisplayName");
        apiStyle = text(apiStyle, "apiStyle");
        apiStyleDisplayName = text(apiStyleDisplayName, "apiStyleDisplayName");
        availability = text(availability, "availability");
        unavailableReason = unavailableReason == null ? "" : unavailableReason.trim();
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities));
        if (contextWindow < 1) throw new IllegalArgumentException("contextWindow must be positive");
        if (maxOutputTokens < 1 || maxOutputTokens > contextWindow) {
            throw new IllegalArgumentException("maxOutputTokens is invalid");
        }
        preferenceSchemaVersion = text(preferenceSchemaVersion, "preferenceSchemaVersion");
        profileVersion = text(profileVersion, "profileVersion");
        profileDigest = text(profileDigest, "profileDigest");
        controls = Objects.requireNonNull(controls, "controls must not be null");
        recommendedPreferences =
                Objects.requireNonNull(recommendedPreferences, "recommendedPreferences must not be null");
    }

    private static String text(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > 256) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
