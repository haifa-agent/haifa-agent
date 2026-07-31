package io.haifa.agent.personalassistant.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record PersonalModelPreference(
        String conversationId,
        String modelId,
        long revision,
        Optional<String> idempotencyKeyDigest,
        Optional<String> requestDigest,
        Instant updatedAt) {
    public PersonalModelPreference {
        conversationId = Objects.requireNonNull(conversationId).trim();
        modelId = Objects.requireNonNull(modelId).trim();
        if (conversationId.isEmpty() || modelId.isEmpty() || revision < 0) {
            throw new IllegalArgumentException("model preference is invalid");
        }
        idempotencyKeyDigest = Objects.requireNonNull(idempotencyKeyDigest);
        requestDigest = Objects.requireNonNull(requestDigest);
        updatedAt = Objects.requireNonNull(updatedAt);
    }
}
