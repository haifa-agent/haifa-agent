package io.haifa.agent.core;

import static io.haifa.agent.core.CoreTestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentCapabilityRequirement;
import io.haifa.agent.core.agent.AgentDefinition;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.agent.AgentOutputContractRef;
import io.haifa.agent.core.agent.AgentType;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.AgentSessionStatus;
import io.haifa.agent.core.session.SessionScope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentDefinitionSessionTest {

    @Test
    void freezesDefinitionVersionCapabilitiesAndNestedMetadata() {
        List<String> tags = new ArrayList<>(List.of("stable"));
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tags", tags);
        AgentDefinition definition = new AgentDefinition(
                new AgentDefinitionId("coding-primary"),
                new AgentDefinitionVersion(1, 0, 0),
                "Coding Agent",
                "Works within a project workspace",
                AgentType.CODING,
                "prompt:coding@1",
                "model:balanced@2",
                Set.of("file.read", "file.patch"),
                Set.of("java"),
                Set.of("reviewer"),
                List.of(new AgentCapabilityRequirement("sandbox", ">=1.0", true)),
                new AgentOutputContractRef("coding-result", "1.0"),
                metadata);

        tags.add("mutated");
        metadata.put("unsafe", true);

        assertThat(definition.version().toString()).isEqualTo("1.0.0");
        assertThat(definition.metadata()).containsOnlyKeys("tags");
        assertThat(definition.metadata().get("tags")).isEqualTo(List.of("stable"));
        assertThatThrownBy(() -> definition.allowedToolNames().add("shell"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void managesSessionLifecycleWithoutAnAgentDefinitionBinding() {
        AgentSession session = AgentSession.open(
                new AgentSessionId("session-project"),
                new TenantRef("local"),
                new PrincipalRef("user-1", "user"),
                new ProjectRef("project-1"),
                SessionScope.PROJECT,
                NOW,
                Map.of("channel", "desktop"));

        session.archive(NOW.plusSeconds(1));
        session.close(NOW.plusSeconds(2));

        assertThat(session.status()).isEqualTo(AgentSessionStatus.CLOSED);
        assertThat(session.closedAt()).contains(NOW.plusSeconds(2));
        assertThat(session.version()).isEqualTo(2);
        assertThat(List.of(AgentSession.class.getDeclaredFields()))
                .noneMatch(field -> field.getName().toLowerCase().contains("agent"));
        assertThatThrownBy(() -> session.archive(NOW.plusSeconds(3))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void projectScopeRequiresAProjectButEphemeralScopeDoesNot() {
        assertThatThrownBy(() -> AgentSession.open(
                        new AgentSessionId("invalid"),
                        new TenantRef("local"),
                        new PrincipalRef("user-1", "user"),
                        null,
                        SessionScope.PROJECT,
                        NOW,
                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project");

        AgentSession ephemeral = AgentSession.open(
                new AgentSessionId("ephemeral"),
                new TenantRef("local"),
                new PrincipalRef("user-1", "user"),
                null,
                SessionScope.EPHEMERAL,
                NOW,
                Map.of());
        assertThat(ephemeral.project()).isEmpty();
    }
}
