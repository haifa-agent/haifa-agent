package io.haifa.agent.runtime.api;

import io.haifa.agent.core.agent.AgentDefinition;
import io.haifa.agent.core.session.AgentSession;
import java.util.Objects;

/** Request to start an Agent run in an existing session. */
public record AgentRunRequest(AgentDefinition agent, AgentSession session, String input) {

    public AgentRunRequest {
        agent = Objects.requireNonNull(agent, "agent must not be null");
        session = Objects.requireNonNull(session, "session must not be null");
        if (!session.agentId().equals(agent.id())) {
            throw new IllegalArgumentException("session must belong to the requested Agent");
        }
        input = requireInput(input);
    }

    private static String requireInput(String value) {
        String normalized =
                Objects.requireNonNull(value, "input must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("input must not be blank");
        }
        return normalized;
    }
}
