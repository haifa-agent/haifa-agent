package io.haifa.agent.sdk.memory;

import io.haifa.agent.memory.api.MemoryCandidateId;
import java.util.Objects;

public record RejectMemoryCandidateCommand(
        MemoryCandidateId candidateId, long expectedRevision, String idempotencyKey, String reason) {
    public RejectMemoryCandidateCommand {
        candidateId = Objects.requireNonNull(candidateId, "candidateId must not be null");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must not be negative");
        idempotencyKey = MemoryScopeSpec.requireText(idempotencyKey, 256);
        reason = MemoryScopeSpec.requireText(reason, 512);
    }
}
