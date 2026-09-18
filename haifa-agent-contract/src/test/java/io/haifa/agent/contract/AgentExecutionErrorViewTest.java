package io.haifa.agent.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.contract.run.AgentExecutionErrorView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentExecutionErrorViewTest {
    @Test
    void deeplyCopiesPublicErrorDetails() {
        List<Object> values = new ArrayList<>(List.of("first"));
        Map<String, Object> nested = new LinkedHashMap<>(Map.of("values", values));
        Map<String, Object> details = new LinkedHashMap<>(Map.of("nested", nested));

        AgentExecutionErrorView view = new AgentExecutionErrorView(
                "RUN_BUDGET_EXCEEDED",
                "Run budget exceeded",
                "RESOURCE_LIMIT",
                "NOT_RETRYABLE",
                details,
                Optional.of("diag-1"),
                Instant.parse("2026-07-31T00:00:00Z"));
        values.add("second");
        nested.put("other", "value");
        details.put("late", true);

        assertThat(view.details()).containsOnlyKeys("nested").isUnmodifiable();
        Map<?, ?> immutableNested = (Map<?, ?>) view.details().get("nested");
        assertThat(immutableNested.keySet()).isEqualTo(Set.of("values"));
        assertThat(immutableNested).isUnmodifiable();
        List<?> immutableValues = (List<?>) immutableNested.get("values");
        assertThat(immutableValues).isEqualTo(List.of("first")).isUnmodifiable();
    }
}
