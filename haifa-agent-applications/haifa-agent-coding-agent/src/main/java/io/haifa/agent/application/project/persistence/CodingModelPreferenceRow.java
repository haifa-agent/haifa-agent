package io.haifa.agent.application.project.persistence;

import java.time.Instant;

public record CodingModelPreferenceRow(
        String sessionId,
        String schemaVersion,
        String modelId,
        long revision,
        String idempotencyKeyDigest,
        String requestDigest,
        Instant updatedAt) {}
