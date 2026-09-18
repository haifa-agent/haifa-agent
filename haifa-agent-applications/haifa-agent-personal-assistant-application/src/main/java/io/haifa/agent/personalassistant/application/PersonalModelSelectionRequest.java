package io.haifa.agent.personalassistant.application;

import java.util.Objects;

public record PersonalModelSelectionRequest(
        String modelBindingId,
        String preferenceSchemaVersion,
        String profileVersion,
        String profileDigest,
        PersonalModelPreferences preferences) {
    public PersonalModelSelectionRequest {
        modelBindingId = text(modelBindingId, "modelBindingId");
        preferenceSchemaVersion = text(preferenceSchemaVersion, "preferenceSchemaVersion");
        profileVersion = text(profileVersion, "profileVersion");
        profileDigest = text(profileDigest, "profileDigest");
        preferences = Objects.requireNonNull(preferences, "preferences must not be null");
    }

    private static String text(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
