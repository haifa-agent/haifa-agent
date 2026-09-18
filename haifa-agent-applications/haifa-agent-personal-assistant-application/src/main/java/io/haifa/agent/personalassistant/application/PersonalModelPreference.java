package io.haifa.agent.personalassistant.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record PersonalModelPreference(
        String conversationId,
        String modelBindingId,
        String preferenceSchemaVersion,
        PersonalModelPreferences userPreferences,
        String preferenceDigest,
        long revision,
        Optional<String> idempotencyKeyDigest,
        Optional<String> requestDigest,
        Instant updatedAt) {
    public PersonalModelPreference {
        conversationId = Objects.requireNonNull(conversationId).trim();
        modelBindingId = Objects.requireNonNull(modelBindingId).trim();
        preferenceSchemaVersion =
                Objects.requireNonNull(preferenceSchemaVersion).trim();
        userPreferences = Objects.requireNonNull(userPreferences);
        preferenceDigest = Objects.requireNonNull(preferenceDigest).trim();
        if (conversationId.isEmpty() || modelBindingId.isEmpty() || preferenceSchemaVersion.isEmpty() || revision < 0) {
            throw new IllegalArgumentException("model preference is invalid");
        }
        if (!preferenceDigest.equals(userPreferences.digest())) {
            throw new IllegalArgumentException("model preference digest is invalid");
        }
        idempotencyKeyDigest = Objects.requireNonNull(idempotencyKeyDigest);
        requestDigest = Objects.requireNonNull(requestDigest);
        updatedAt = Objects.requireNonNull(updatedAt);
    }
}
