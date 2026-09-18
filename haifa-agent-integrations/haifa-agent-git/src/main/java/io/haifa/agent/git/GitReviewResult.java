package io.haifa.agent.git;

import java.util.List;
import java.util.Objects;

/** Bounded Git review evidence derived from status plus diff metadata. */
public record GitReviewResult(
        String evidenceDigest, List<GitReviewChange> changes, boolean truncated, boolean complete) {
    public GitReviewResult {
        evidenceDigest = Objects.requireNonNull(evidenceDigest, "evidenceDigest must not be null");
        if (!evidenceDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("evidenceDigest must be a SHA-256 digest");
        }
        changes = List.copyOf(Objects.requireNonNull(changes, "changes must not be null"));
    }
}
