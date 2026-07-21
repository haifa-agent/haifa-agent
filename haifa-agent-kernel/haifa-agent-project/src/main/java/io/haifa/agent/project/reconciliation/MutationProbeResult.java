package io.haifa.agent.project.reconciliation;

import java.util.Objects;

public record MutationProbeResult(MutationProbeStatus status, String safeDetail) {
    public MutationProbeResult {
        status = Objects.requireNonNull(status, "status must not be null");
        safeDetail = Objects.requireNonNull(safeDetail, "safeDetail must not be null")
                .trim();
        if (safeDetail.isEmpty()) throw new IllegalArgumentException("safeDetail must not be blank");
    }
}
