package io.haifa.agent.sdk.memory;

import io.haifa.agent.memory.api.MemoryCandidateId;
import java.util.Objects;

public record ReviewMemoryCandidateCommand(
        MemoryCandidateId candidateId, long expectedRevision, String idempotencyKey) {
    public ReviewMemoryCandidateCommand {
        candidateId = Objects.requireNonNull(candidateId, "candidateId must not be null");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must not be negative");
        idempotencyKey = MemoryScopeSpec.requireText(idempotencyKey, 256);
    }
}
