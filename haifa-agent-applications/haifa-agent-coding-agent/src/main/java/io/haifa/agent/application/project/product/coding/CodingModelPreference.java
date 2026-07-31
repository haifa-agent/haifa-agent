package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Persisted Coding Session reference to one trusted internal model id. */
public record CodingModelPreference(
        AgentSessionId sessionId,
        String modelId,
        long revision,
        Optional<String> idempotencyKeyDigest,
        Optional<String> requestDigest,
        Instant updatedAt) {
    public CodingModelPreference {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        modelId = CodingProductValues.requireText(modelId, "modelId", 128);
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        idempotencyKeyDigest = Objects.requireNonNull(idempotencyKeyDigest, "idempotencyKeyDigest must not be null");
        requestDigest = Objects.requireNonNull(requestDigest, "requestDigest must not be null");
        if (idempotencyKeyDigest.isPresent() != requestDigest.isPresent()) {
            throw new IllegalArgumentException("idempotency and request digests must be present together");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static CodingModelPreference initial(AgentSessionId sessionId, String modelId, Instant updatedAt) {
        return new CodingModelPreference(sessionId, modelId, 0, Optional.empty(), Optional.empty(), updatedAt);
    }
}
