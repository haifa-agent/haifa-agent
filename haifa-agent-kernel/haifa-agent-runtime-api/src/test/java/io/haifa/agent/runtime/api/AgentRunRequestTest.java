package io.haifa.agent.runtime.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentRunRequestTest {
    @Test
    void exposesOnlyStartIntentAndDefensivelyCopiesInputsAndOverrides() {
        List<ContentPart> inputs = new ArrayList<>(List.of(new TextPart("context", "plain")));
        Map<String, Object> values = new HashMap<>(Map.of("maxIterations", 10));
        AgentRunRequest request = new AgentRunRequest(
                "start-1",
                new AgentDefinitionId("coding-agent"),
                Optional.of(new AgentDefinitionVersion(1, 2, 0)),
                "coding",
                new AgentSessionId("session-1"),
                Optional.of(new ProjectRef("project-1")),
                "  inspect the repository  ",
                inputs,
                new RuntimeOverrides("runtime.overrides", "1.0", values));
        inputs.clear();
        values.put("maxIterations", 99);

        assertThat(request.objective()).isEqualTo("inspect the repository");
        assertThat(request.inputs()).hasSize(1);
        assertThat(request.overrides().values()).containsEntry("maxIterations", 10);
        assertThat(AgentRunRequest.class.getRecordComponents())
                .noneMatch(component -> component.getType().getSimpleName().contains("Snapshot"));
    }

    @Test
    void rejectsBlankIntentAndUnapprovedOverrideKeys() {
        assertThatThrownBy(() -> request(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("objective");
        assertThatThrownBy(() -> new RuntimeOverrides("runtime.overrides", "1.0", Map.of("providerSecret", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported");
        List<ContentPart> invalidInputs = new ArrayList<>();
        invalidInputs.add(null);
        assertThatThrownBy(() -> new AgentRunRequest(
                        "start-1",
                        new AgentDefinitionId("coding-agent"),
                        Optional.empty(),
                        "coding",
                        new AgentSessionId("session-1"),
                        Optional.empty(),
                        "objective",
                        invalidInputs,
                        RuntimeOverrides.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputs");
    }

    private static AgentRunRequest request(String objective) {
        return new AgentRunRequest(
                "start-1",
                new AgentDefinitionId("coding-agent"),
                Optional.empty(),
                "coding",
                new AgentSessionId("session-1"),
                Optional.empty(),
                objective,
                List.of(),
                RuntimeOverrides.NONE);
    }
}
