package io.haifa.agent.project.snapshot;

import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record WorkspaceSnapshot(
        WorkspaceSnapshotId id,
        ProjectId projectId,
        WorkspaceId workspaceId,
        WorkspaceRevision baseRevision,
        WorkspaceRevision resultRevision,
        WorkspaceSnapshotStrategy strategy,
        String providerId,
        String providerVersion,
        WorkspaceSnapshotStatus status,
        WorkspaceSnapshotEvidence evidence,
        String contentDigest,
        String runRef,
        String checkpointRef,
        List<String> changeSetRefs,
        String retentionPolicy,
        Instant createdAt) {
    public WorkspaceSnapshot {
        id = Objects.requireNonNull(id, "id must not be null");
        projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        baseRevision = Objects.requireNonNull(baseRevision, "baseRevision must not be null");
        resultRevision = Objects.requireNonNull(resultRevision, "resultRevision must not be null");
        strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        providerId = require(providerId, "providerId");
        providerVersion = require(providerVersion, "providerVersion");
        status = Objects.requireNonNull(status, "status must not be null");
        evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        contentDigest = require(contentDigest, "contentDigest");
        runRef = require(runRef, "runRef");
        checkpointRef = optional(checkpointRef);
        changeSetRefs = List.copyOf(Objects.requireNonNull(changeSetRefs, "changeSetRefs must not be null"));
        retentionPolicy = require(retentionPolicy, "retentionPolicy");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public WorkspaceSnapshot invalidate() {
        if (status != WorkspaceSnapshotStatus.CAPTURED) {
            throw new IllegalStateException("only captured snapshot can be invalidated");
        }
        return withStatus(WorkspaceSnapshotStatus.INVALID);
    }

    public WorkspaceSnapshot release() {
        if (status == WorkspaceSnapshotStatus.RELEASED) {
            throw new IllegalStateException("snapshot is already released");
        }
        return withStatus(WorkspaceSnapshotStatus.RELEASED);
    }

    private WorkspaceSnapshot withStatus(WorkspaceSnapshotStatus target) {
        return new WorkspaceSnapshot(
                id,
                projectId,
                workspaceId,
                baseRevision,
                resultRevision,
                strategy,
                providerId,
                providerVersion,
                target,
                evidence,
                contentDigest,
                runRef,
                checkpointRef,
                changeSetRefs,
                retentionPolicy,
                createdAt);
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
