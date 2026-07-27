package io.haifa.agent.application.project.persistence;

public record ProjectProductSessionRow(
        String sessionId,
        String schemaVersion,
        String projectId,
        String workspaceId,
        String tenantId,
        String principalId,
        String principalType,
        String configurationId,
        String configurationVersion,
        String configurationDigest,
        String productProfileRef) {}
