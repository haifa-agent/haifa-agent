package io.haifa.agent.runtime.api;

import io.haifa.agent.core.session.AgentSessionId;
import java.util.Objects;

/** Transport-ready run identity plus the authoritative Runtime snapshot. */
public record AgentRunViewSnapshot(AgentSessionId sessionId, AgentRunSnapshot snapshot) {
    public AgentRunViewSnapshot {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
    }
}
