package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.git.GitRepositoryRef;
import java.util.Objects;

/** Bounded Git state captured before this Run first writes to a repository. */
public record RepositoryBaseline(
        GitRepositoryRef repository,
        String headRevision,
        String dirtySnapshotDigest,
        AttributionStatus attributionStatus) {
    public RepositoryBaseline {
        repository = Objects.requireNonNull(repository, "repository must not be null");
        headRevision = bounded(headRevision, "headRevision", 128, true);
        dirtySnapshotDigest = bounded(dirtySnapshotDigest, "dirtySnapshotDigest", 71, false);
        if (!dirtySnapshotDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("dirtySnapshotDigest must be a SHA-256 digest");
        }
        attributionStatus = Objects.requireNonNull(attributionStatus, "attributionStatus must not be null");
    }

    private static String bounded(String value, String field, int maximum, boolean optional) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if ((!optional && normalized.isEmpty()) || normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
