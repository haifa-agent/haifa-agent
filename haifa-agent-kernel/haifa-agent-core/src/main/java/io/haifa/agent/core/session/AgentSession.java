package io.haifa.agent.core.session;

import io.haifa.agent.core.agent.AgentId;
import java.time.Instant;
import java.util.Objects;

/** Conversation boundary shared by multiple Agent runs. */
public record AgentSession(SessionId id, AgentId agentId, Instant createdAt) {

    public AgentSession {
        id = Objects.requireNonNull(id, "id must not be null");
        agentId = Objects.requireNonNull(agentId, "agentId must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
