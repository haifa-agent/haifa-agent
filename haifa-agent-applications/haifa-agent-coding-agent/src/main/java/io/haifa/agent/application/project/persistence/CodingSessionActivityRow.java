package io.haifa.agent.application.project.persistence;

import java.time.Instant;

public record CodingSessionActivityRow(
        String sessionId,
        String schemaVersion,
        String projectId,
        String tenantId,
        String principalId,
        String principalType,
        String displayName,
        String activeRunId,
        Long activeRunVersion,
        String activeDispatchKey,
        Instant createdAt,
        Instant lastActivityAt,
        long revision) {}
