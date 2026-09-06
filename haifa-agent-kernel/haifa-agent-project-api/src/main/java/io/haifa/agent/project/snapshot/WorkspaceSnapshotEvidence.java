package io.haifa.agent.project.snapshot;

import java.util.Objects;

public record WorkspaceSnapshotEvidence(
        String rootFingerprint,
        String manifestDigest,
        String gitRepositoryIdentity,
        String gitCommit,
        String gitTree,
        String uncommittedPatchRef,
        WorkspaceSnapshotPayloadRef payload,
        boolean consistent) {
    public WorkspaceSnapshotEvidence {
        rootFingerprint = require(rootFingerprint, "rootFingerprint");
        manifestDigest = require(manifestDigest, "manifestDigest");
        gitRepositoryIdentity = optional(gitRepositoryIdentity);
        gitCommit = optional(gitCommit);
        gitTree = optional(gitTree);
        uncommittedPatchRef = optional(uncommittedPatchRef);
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
