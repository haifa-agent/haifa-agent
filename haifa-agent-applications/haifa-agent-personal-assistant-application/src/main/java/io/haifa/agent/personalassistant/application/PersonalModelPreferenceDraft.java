package io.haifa.agent.personalassistant.application;

import java.util.Objects;

public record PersonalModelPreferenceDraft(
        String modelBindingId,
        String preferenceSchemaVersion,
        PersonalModelPreferences userPreferences,
        String preferenceDigest) {
    public PersonalModelPreferenceDraft {
        modelBindingId = text(modelBindingId, "modelBindingId");
        preferenceSchemaVersion = text(preferenceSchemaVersion, "preferenceSchemaVersion");
        userPreferences = Objects.requireNonNull(userPreferences, "userPreferences must not be null");
        preferenceDigest = text(preferenceDigest, "preferenceDigest");
        if (!preferenceDigest.equals(userPreferences.digest())) {
            throw new IllegalArgumentException("preferenceDigest does not match userPreferences");
        }
    }

    public static PersonalModelPreferenceDraft from(PersonalResolvedModelSelection selection) {
        return new PersonalModelPreferenceDraft(
                selection.option().id(),
                selection.option().preferenceSchemaVersion(),
                selection.preferences(),
                selection.preferences().digest());
    }

    private static String text(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
