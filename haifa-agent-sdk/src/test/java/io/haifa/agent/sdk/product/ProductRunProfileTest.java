package io.haifa.agent.sdk.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ProductRunProfileTest {
    @Test
    void deeplyFreezesJsonCompatibleModelOptions() {
        List<Object> types = new ArrayList<>(List.of("json_object"));
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("types", types);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("response_format", format);

        ProductRunProfile profile = profile(options);
        types.add("text");
        format.put("unexpected", true);

        assertThat(profile.modelRequestOptions())
                .isEqualTo(Map.of("response_format", Map.of("types", List.of("json_object"))));
        assertThatThrownBy(() -> ((Map<String, Object>)
                                profile.modelRequestOptions().get("response_format"))
                        .put("type", "text"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNonJsonCompatibleModelOptions() {
        assertThatThrownBy(() -> profile(Map.of("response_format", new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON-compatible");
    }

    @Test
    void freezesOptionalToolAllowlistWithoutConflatingEmptyWithInheritance() {
        Set<String> aliases = new java.util.LinkedHashSet<>(Set.of("web_search"));
        ProductRunProfile restricted = new ProductRunProfile(
                "planner",
                "v1",
                "model",
                AgentRunType.CHAT,
                new AgentRunBudget(1_000, 1_000, 1_000, 2, 2, 0, "USD", 100),
                new AgentRunLimits(2, 0, 1, 10_000, 10_000),
                Map.of(),
                Optional.of(aliases));
        aliases.add("shell");

        assertThat(restricted.allowedTools()).contains(Set.of("web_search"));
        assertThat(profile(Map.of()).allowedTools()).isEmpty();
        assertThat(new ProductRunProfile(
                                "synthesis",
                                "v1",
                                "model",
                                AgentRunType.CHAT,
                                new AgentRunBudget(1_000, 1_000, 1_000, 0, 1, 0, "USD", 100),
                                new AgentRunLimits(2, 0, 1, 10_000, 10_000),
                                Map.of(),
                                Optional.of(Set.of()))
                        .allowedTools())
                .contains(Set.of());
    }

    private static ProductRunProfile profile(Map<String, Object> options) {
        return new ProductRunProfile(
                "planner",
                "v1",
                "model",
                AgentRunType.CHAT,
                new AgentRunBudget(1_000, 1_000, 1_000, 2, 2, 0, "USD", 100),
                new AgentRunLimits(2, 0, 1, 10_000, 10_000),
                options);
    }
}
