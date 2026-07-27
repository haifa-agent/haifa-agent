package io.haifa.agent.application.project.admin;

import java.time.Instant;

public record FileChangeSetView(
        String changeSetId,
        String workspaceId,
        String status,
        long baseRevision,
        Long resultRevision,
        int changedFileCount,
        String runRef,
        Instant createdAt) {}
