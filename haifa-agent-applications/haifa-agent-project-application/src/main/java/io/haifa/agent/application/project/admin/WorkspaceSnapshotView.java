package io.haifa.agent.application.project.admin;

import java.time.Instant;

public record WorkspaceSnapshotView(
        String snapshotId,
        String workspaceId,
        String strategy,
        String status,
        long baseRevision,
        long resultRevision,
        String contentDigest,
        String providerRef,
        String runRef,
        String checkpointRef,
        Instant createdAt) {}
