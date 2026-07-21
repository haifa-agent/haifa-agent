package io.haifa.agent.application.project.checkpoint;

import io.haifa.agent.project.snapshot.WorkspaceSnapshotId;
import io.haifa.agent.project.snapshot.WorkspaceSnapshotStrategy;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Objects;

public record WorkspaceCheckpointState(
        WorkspaceSnapshotId snapshotId,
        WorkspaceId workspaceId,
        String bindingRef,
        WorkspaceSnapshotStrategy strategy,
        String providerId,
        String providerVersion,
        String snapshotDigest) {
    public WorkspaceCheckpointState {
        snapshotId = Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        bindingRef = require(bindingRef, "bindingRef");
        strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        providerId = require(providerId, "providerId");
        providerVersion = require(providerVersion, "providerVersion");
        snapshotDigest = require(snapshotDigest, "snapshotDigest");
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
