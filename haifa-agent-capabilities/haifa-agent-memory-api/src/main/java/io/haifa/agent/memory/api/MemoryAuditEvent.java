package io.haifa.agent.memory.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record MemoryAuditEvent(
        String operation,
        Optional<MemoryCandidateId> candidateId,
        Optional<MemoryRef> memory,
        MemoryScope scope,
        String actorId,
        Map<String, String> safeAttributes,
        Instant occurredAt,
        Optional<String> idempotencyKeyDigest,
        Optional<String> requestDigest,
        Optional<Long> candidateRevision) {
    public MemoryAuditEvent {
        operation = MemoryValues.text(operation, "operation", 128);
        candidateId = Objects.requireNonNull(candidateId);
        memory = Objects.requireNonNull(memory);
        scope = Objects.requireNonNull(scope);
        actorId = MemoryValues.text(actorId, "actorId", 256);
        safeAttributes = Map.copyOf(Objects.requireNonNull(safeAttributes));
        occurredAt = Objects.requireNonNull(occurredAt);
        idempotencyKeyDigest = Objects.requireNonNull(idempotencyKeyDigest, "idempotencyKeyDigest must not be null")
                .map(value -> MemoryValues.text(value, "idempotencyKeyDigest", 128));
        requestDigest = Objects.requireNonNull(requestDigest, "requestDigest must not be null")
                .map(value -> MemoryValues.text(value, "requestDigest", 128));
        candidateRevision = Objects.requireNonNull(candidateRevision, "candidateRevision must not be null");
        candidateRevision.ifPresent(value -> {
            if (value < 0) throw new IllegalArgumentException("candidateRevision must not be negative");
        });
        if (idempotencyKeyDigest.isPresent() != requestDigest.isPresent()) {
            throw new IllegalArgumentException("idempotency and request digests must be present together");
        }
    }

    public MemoryAuditEvent(
            String operation,
            Optional<MemoryCandidateId> candidateId,
            Optional<MemoryRef> memory,
            MemoryScope scope,
            String actorId,
            Map<String, String> safeAttributes,
            Instant occurredAt) {
        this(
                operation,
                candidateId,
                memory,
                scope,
                actorId,
                safeAttributes,
                occurredAt,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
