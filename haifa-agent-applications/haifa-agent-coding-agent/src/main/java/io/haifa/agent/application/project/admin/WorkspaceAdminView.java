package io.haifa.agent.application.project.admin;

import java.time.Instant;

public record WorkspaceAdminView(
        String projectId,
        String workspaceId,
        String purpose,
        String status,
        long revision,
        String revisionDigest,
        WorkspaceBindingView binding,
        Instant updatedAt) {}
