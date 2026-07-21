package io.haifa.agent.memory.api;

import java.time.Instant;
import java.util.Objects;

public record MemoryTombstone(
        MemoryRef memory, MemoryScope scope, String formerContentDigest, String reason, Instant purgedAt) {
    public MemoryTombstone {
        memory = Objects.requireNonNull(memory);
        scope = Objects.requireNonNull(scope);
        formerContentDigest = MemoryValues.text(formerContentDigest, "formerContentDigest", 128);
        reason = MemoryValues.text(reason, "reason", 512);
        purgedAt = Objects.requireNonNull(purgedAt);
    }
}
