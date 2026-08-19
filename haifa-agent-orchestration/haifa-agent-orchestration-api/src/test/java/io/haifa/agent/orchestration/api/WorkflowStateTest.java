package io.haifa.agent.orchestration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkflowStateTest {
    private static final WorkflowStateSchema SCHEMA =
            new WorkflowStateSchema("state", 1, Set.of("name", "nested"), 8, 3, 8);

    @Test
    void stateIsDeeplyImmutableAndKeyOrdered() {
        List<Object> nested = new ArrayList<>(List.of("value"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("nested", nested);
        input.put("name", "fixture");

        WorkflowState state = new WorkflowState(SCHEMA, input);
        nested.add("changed");
        input.put("name", "changed");

        assertThat(state.values().keySet()).containsExactly("name", "nested");
        assertThat(state.values()).containsEntry("name", "fixture");
        assertThat(state.values().get("nested")).isEqualTo(List.of("value"));
        assertThatThrownBy(() -> state.values().put("name", "blocked"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void stateRejectsUnknownKeysDepthAndUnstableNumberTypes() {
        assertThatThrownBy(() -> new WorkflowState(SCHEMA, Map.of("unknown", "value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowState(SCHEMA, Map.of("nested", List.of(List.of(List.of("too-deep"))))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowState(SCHEMA, Map.of("nested", 1.25d)))
                .isInstanceOf(IllegalArgumentException.class);
        WorkflowStateSchema tiny = new WorkflowStateSchema("tiny", 1, Set.of("nested"), 2, 4, 8);
        assertThatThrownBy(() -> new WorkflowState(tiny, Map.of("nested", List.of("one", "two"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
