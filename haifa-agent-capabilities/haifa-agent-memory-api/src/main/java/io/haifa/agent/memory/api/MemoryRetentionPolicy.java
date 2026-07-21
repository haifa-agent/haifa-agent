package io.haifa.agent.memory.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record MemoryRetentionPolicy(String policyId, Optional<Instant> expiresAt, boolean purgeAfterExpiry) {
    public static final MemoryRetentionPolicy RETAIN = new MemoryRetentionPolicy("retain-v1", Optional.empty(), false);

    public MemoryRetentionPolicy {
        policyId = MemoryValues.text(policyId, "policyId", 128);
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
