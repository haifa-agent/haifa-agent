package io.haifa.agent.git;

import java.util.Objects;

/** Bounded baseline evidence; dirty content is represented only by a digest. */
public record GitReviewSnapshot(String headRevision, String dirtySnapshotDigest, boolean complete) {
    public GitReviewSnapshot {
        headRevision = Objects.requireNonNull(headRevision, "headRevision must not be null")
                .trim();
        if (headRevision.length() > 128) throw new IllegalArgumentException("headRevision exceeds its bound");
        dirtySnapshotDigest = Objects.requireNonNull(dirtySnapshotDigest, "dirtySnapshotDigest must not be null");
        if (!dirtySnapshotDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("dirtySnapshotDigest must be a SHA-256 digest");
        }
    }
}
