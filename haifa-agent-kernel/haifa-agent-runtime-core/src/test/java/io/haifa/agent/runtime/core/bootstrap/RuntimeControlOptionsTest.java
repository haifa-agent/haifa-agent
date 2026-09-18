package io.haifa.agent.runtime.core.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunBudget;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeControlOptionsTest {
    private static final AgentRunBudget BUDGET = new AgentRunBudget(100, 100, 100, 20, 10, 0, "USD", 0);

    @Test
    void freezesAThresholdBelowTheHardBudgetAndRemovesItFromProviderOptions() {
        Map<String, Object> options = Map.of(
                RuntimeControlOptions.FINALIZE_AFTER_TOOL_CALLS,
                12,
                RuntimeControlOptions.MAX_REASONING_BYTES,
                524_288,
                RuntimeControlOptions.MAX_REASONING_DURATION_MILLIS,
                300_000,
                "response_format",
                Map.of("type", "json_object"));

        RuntimeControlOptions.validate(options, BUDGET);

        assertThat(RuntimeControlOptions.finalizeOnly(options, 11)).isFalse();
        assertThat(RuntimeControlOptions.finalizeOnly(options, 12)).isTrue();
        assertThat(RuntimeControlOptions.maxReasoningBytes(options)).hasValue(524_288);
        assertThat(RuntimeControlOptions.maxReasoningDurationMillis(options)).hasValue(300_000);
        assertThat(RuntimeControlOptions.providerOptions(options))
                .isEqualTo(Map.of("response_format", Map.of("type", "json_object")));
    }

    @Test
    void rejectsInvalidOrUnknownRuntimeControls() {
        assertThatThrownBy(() -> RuntimeControlOptions.validate(
                        Map.of(RuntimeControlOptions.FINALIZE_AFTER_TOOL_CALLS, 20), BUDGET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lower than the hard Tool-call budget");
        assertThatThrownBy(() -> RuntimeControlOptions.validate(Map.of("haifa.runtime.unknown", 1), BUDGET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported Runtime control option");
        assertThatThrownBy(() ->
                        RuntimeControlOptions.validate(Map.of(RuntimeControlOptions.MAX_REASONING_BYTES, 0), BUDGET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive integer");
    }
}
