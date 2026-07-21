package io.haifa.agent.memory.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record MemoryConflict(
        String id,
        MemoryRef existingMemory,
        MemoryCandidateId candidateId,
        String reason,
        String policyVersion,
        Optional<MemoryConflictResolution> resolution,
        Optional<String> resolutionIdempotencyKey,
        Instant createdAt,
        Optional<Instant> resolvedAt) {
    public MemoryConflict {
        id = MemoryValues.text(id, "id", 256);
        existingMemory = Objects.requireNonNull(existingMemory, "existingMemory must not be null");
        candidateId = Objects.requireNonNull(candidateId, "candidateId must not be null");
        reason = MemoryValues.text(reason, "reason", 512);
        policyVersion = MemoryValues.text(policyVersion, "policyVersion", 128);
        resolution = Objects.requireNonNull(resolution, "resolution must not be null");
        resolutionIdempotencyKey = Objects.requireNonNull(resolutionIdempotencyKey);
        createdAt = Objects.requireNonNull(createdAt);
        resolvedAt = Objects.requireNonNull(resolvedAt);
    }

    public MemoryConflict resolve(MemoryConflictResolution value, String idempotencyKey, Instant at) {
        if (resolution.isPresent()) {
            if (resolution.orElseThrow() == value
                    && resolutionIdempotencyKey.orElseThrow().equals(idempotencyKey)) return this;
            throw new IllegalStateException("conflict already resolved");
        }
        return new MemoryConflict(
                id,
                existingMemory,
                candidateId,
                reason,
                policyVersion,
                Optional.of(value),
                Optional.of(MemoryValues.text(idempotencyKey, "idempotencyKey", 256)),
                createdAt,
                Optional.of(Objects.requireNonNull(at)));
    }
}
