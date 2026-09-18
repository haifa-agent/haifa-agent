package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.Objects;

public record CodingSessionCursor(Instant lastActivityAt, AgentSessionId sessionId) {
    public CodingSessionCursor {
        lastActivityAt = Objects.requireNonNull(lastActivityAt, "lastActivityAt must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
    }
}
