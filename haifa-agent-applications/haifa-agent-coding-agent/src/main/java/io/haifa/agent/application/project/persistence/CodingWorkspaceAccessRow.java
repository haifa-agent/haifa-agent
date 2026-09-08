package io.haifa.agent.application.project.persistence;

public record CodingWorkspaceAccessRow(
        String tenantId, String principalType, String principalId, String workspaceId, String mode) {}
