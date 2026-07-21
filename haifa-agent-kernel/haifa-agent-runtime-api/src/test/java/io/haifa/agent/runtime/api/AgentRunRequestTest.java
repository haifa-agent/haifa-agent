package io.haifa.agent.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.session.AgentSessionId;
import org.junit.jupiter.api.Test;

class AgentRunRequestTest {

    @Test
    void usesStableIdsVersionsAndConfigurationSnapshotInsteadOfAggregates() {
        AgentSessionId sessionId = new AgentSessionId("session-1");
        AgentRunRequest coding = request("coding-agent", sessionId);
        AgentRunRequest reviewer = request("review-agent", sessionId);

        assertThat(coding.objective()).isEqualTo("inspect the repository");
        assertThat(coding.sessionId()).isEqualTo(reviewer.sessionId());
        assertThat(coding.agentDefinitionId()).isNotEqualTo(reviewer.agentDefinitionId());
        assertThat(coding.configurationSnapshot().contentHash()).isEqualTo("sha256:abc");
    }

    @Test
    void rejectsBlankObjectives() {
        assertThatThrownBy(() -> new AgentRunRequest(
                        new AgentDefinitionId("coding-agent"),
                        new AgentDefinitionVersion(1, 0, 0),
                        new AgentSessionId("session-1"),
                        " ",
                        new RunConfigurationSnapshotRef("snapshot-1", "sha256:abc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("objective");
    }

    private static AgentRunRequest request(String definitionId, AgentSessionId sessionId) {
        return new AgentRunRequest(
                new AgentDefinitionId(definitionId),
                new AgentDefinitionVersion(1, 0, 0),
                sessionId,
                "  inspect the repository  ",
                new RunConfigurationSnapshotRef("snapshot-1", "sha256:abc"));
    }
}
