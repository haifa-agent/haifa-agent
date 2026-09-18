package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.AgentSessionStatus;
import io.haifa.agent.project.domain.ProjectId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public record CodingSessionActivity(
        AgentSessionId sessionId,
        ProjectId projectId,
        TenantRef tenant,
        PrincipalRef principal,
        String displayName,
        AgentSessionStatus status,
        Optional<AgentRunId> activeRunId,
        OptionalLong activeRunVersion,
        Optional<String> activeDispatchKey,
        Instant createdAt,
        Instant lastActivityAt,
        long revision) {
    public CodingSessionActivity {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        displayName = CodingProductValues.requireText(displayName, "displayName", 120);
        status = Objects.requireNonNull(status, "status must not be null");
        activeRunId = Objects.requireNonNull(activeRunId, "activeRunId must not be null");
        activeRunVersion = Objects.requireNonNull(activeRunVersion, "activeRunVersion must not be null");
        activeDispatchKey = Objects.requireNonNull(activeDispatchKey, "activeDispatchKey must not be null")
                .map(value -> CodingProductValues.requireText(value, "activeDispatchKey", 256));
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        lastActivityAt = Objects.requireNonNull(lastActivityAt, "lastActivityAt must not be null");
        if (lastActivityAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("lastActivityAt precedes createdAt");
        }
        if (activeRunId.isPresent() != activeRunVersion.isPresent()) {
            throw new IllegalArgumentException("active Run id and version must be present together");
        }
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
    }
}
