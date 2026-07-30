package io.haifa.agent.application.project.product.coding.verification;

import java.time.Instant;
import java.util.Objects;

public record CodingVerificationEvidence(
        CodingVerificationEvidenceKind kind,
        String planDigest,
        CodingVerificationDimension dimension,
        String sourceRef,
        String terminalStatus,
        String safeSummary,
        Instant observedAt,
        String sourceDigest) {
    public CodingVerificationEvidence {
        kind = Objects.requireNonNull(kind, "kind must not be null");
        planDigest = text(planDigest, "planDigest", 71);
        dimension = Objects.requireNonNull(dimension, "dimension must not be null");
        sourceRef = text(sourceRef, "sourceRef", 320);
        terminalStatus = text(terminalStatus, "terminalStatus", 64);
        safeSummary = text(safeSummary, "safeSummary", 256);
        observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
        sourceDigest = text(sourceDigest, "sourceDigest", 71);
        if (!planDigest.matches("sha256:[0-9a-f]{64}") || !sourceDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("verification evidence digests must be canonical SHA-256 references");
        }
    }

    private static String text(String value, String field, int maximumLength) {
        String result =
                Objects.requireNonNull(value, field + " must not be null").strip();
        if (result.isEmpty() || result.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is blank or too long");
        }
        return result;
    }
}
