package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.domain.ProjectId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record CodingSessionSummary(
        AgentSessionId sessionId,
        ProjectId projectId,
        String displayName,
        Optional<AgentRunId> activeRunId,
        Optional<AgentRunStatus> activeRunStatus,
        int queuedCount,
        Instant lastActivityAt,
        long revision) {
    public CodingSessionSummary {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        displayName = CodingProductValues.requireText(displayName, "displayName", 120);
        activeRunId = Objects.requireNonNull(activeRunId, "activeRunId must not be null");
        activeRunStatus = Objects.requireNonNull(activeRunStatus, "activeRunStatus must not be null");
        if (activeRunId.isPresent() != activeRunStatus.isPresent()) {
            throw new IllegalArgumentException("active Run id and status must be present together");
        }
        if (queuedCount < 0) throw new IllegalArgumentException("queuedCount must not be negative");
        lastActivityAt = Objects.requireNonNull(lastActivityAt, "lastActivityAt must not be null");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
    }
}
