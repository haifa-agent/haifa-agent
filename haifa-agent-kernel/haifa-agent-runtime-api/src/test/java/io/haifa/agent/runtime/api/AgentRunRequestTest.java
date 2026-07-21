package io.haifa.agent.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentDefinition;
import io.haifa.agent.core.agent.AgentId;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.SessionId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentRunRequestTest {

    @Test
    void acceptsSessionOwnedByAgent() {
        AgentId agentId = AgentId.create();
        AgentDefinition agent = new AgentDefinition(agentId, "Coding Agent", "Work inside the project.");
        AgentSession session = new AgentSession(SessionId.create(), agentId, Instant.EPOCH);

        AgentRunRequest request = new AgentRunRequest(agent, session, "  inspect the repository  ");

        assertThat(request.input()).isEqualTo("inspect the repository");
    }

    @Test
    void rejectsSessionOwnedByAnotherAgent() {
        AgentDefinition agent = new AgentDefinition(AgentId.create(), "Coding Agent", "Work inside the project.");
        AgentSession session = new AgentSession(SessionId.create(), AgentId.create(), Instant.EPOCH);

        assertThatThrownBy(() -> new AgentRunRequest(agent, session, "inspect"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belong");
    }
}
