package io.haifa.agent.application.project.product.coding.delivery;

import java.time.Instant;
import java.util.Objects;

public record CodingDeliveryEvidence(
        String evidenceId,
        CodingDeliveryEvidenceKind kind,
        String sourceRef,
        String sourceDigest,
        Instant observedAt,
        String safeSummary) {
    public CodingDeliveryEvidence {
        evidenceId = text(evidenceId, "evidenceId", 256);
        kind = Objects.requireNonNull(kind, "kind must not be null");
        sourceRef = text(sourceRef, "sourceRef", 256);
        sourceDigest = text(sourceDigest, "sourceDigest", 128);
        observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
        safeSummary = text(safeSummary, "safeSummary", 256);
    }

    private static String text(String value, String field, int maximumLength) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is blank or too long");
        }
        return normalized;
    }
}
