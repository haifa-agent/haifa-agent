package io.haifa.agent.runtime.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.*;
import io.haifa.agent.runtime.core.bootstrap.DefaultResolvedModelSnapshots;
import io.haifa.agent.runtime.core.bootstrap.RuntimeControlOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelInputAccountingTest {
    @Test
    void visibleComponentsReconcileWithoutReadingOrReportingProtectedContinuation() {
        var message = ModelMessage.assistant("visible", List.of(), SensitiveModelReasoning.of("never serialize this"));
        var request = new AgentChatRequest(
                new ModelCallId("call"),
                new AgentRunId("run"),
                1,
                1,
                DefaultResolvedModelSnapshots.deepSeekV4Pro(),
                List.of(message),
                List.of(),
                256,
                Duration.ofSeconds(1),
                Map.of());
        var estimate = ModelInputAccounting.estimate(request, Map.of("currentRunAssistant", 3L));
        assertThat(estimate)
                .containsEntry("modelBoundVisibleEstimatedTokens", 9L)
                .containsEntry("protectedMessageCount", 1L)
                .containsEntry("unknownComponents", List.of("protectedContinuation", "providerFormatting"));
        assertThat(estimate.toString().contains("never serialize")).isFalse();
        assertThatThrownBy(() -> ModelInputAccounting.estimate(request, Map.of("currentRunAssistant", 4L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void diagnosticsControlsAreFrozenRuntimeOptionsAndNeverReachTheProvider() {
        assertThat(RuntimeControlOptions.inputAccountingEnabled(Map.of())).isTrue();
        assertThat(RuntimeControlOptions.inputAccountingEnabled(
                        Map.of(RuntimeControlOptions.INPUT_ACCOUNTING_ENABLED, false)))
                .isFalse();
        assertThat(RuntimeControlOptions.providerOptions(
                        Map.of(RuntimeControlOptions.INPUT_ACCOUNTING_ENABLED, true, "temperature", 0)))
                .containsOnlyKeys("temperature");
        assertThatThrownBy(() -> RuntimeControlOptions.inputAccountingEnabled(
                        Map.of(RuntimeControlOptions.INPUT_ACCOUNTING_ENABLED, "false")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
