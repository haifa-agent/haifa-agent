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
        Instant occurredAt) {
    public MemoryAuditEvent {
        operation = MemoryValues.text(operation, "operation", 128);
        candidateId = Objects.requireNonNull(candidateId);
        memory = Objects.requireNonNull(memory);
        scope = Objects.requireNonNull(scope);
        actorId = MemoryValues.text(actorId, "actorId", 256);
        safeAttributes = Map.copyOf(Objects.requireNonNull(safeAttributes));
        occurredAt = Objects.requireNonNull(occurredAt);
    }
}
