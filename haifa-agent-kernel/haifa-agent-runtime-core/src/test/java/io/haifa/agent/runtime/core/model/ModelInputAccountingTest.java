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
    void admissionAddsSchemaWrappersAndExplicitUnknownMediaAndContinuationReserves() {
        var model = DefaultResolvedModelSnapshots.deepSeekV4Pro();
        var protectedMessage = ModelMessage.assistant("visible", List.of(), SensitiveModelReasoning.of("protected"));
        var media = ModelMessage.user(
                "diagram", List.of(new ImageUrlPart(java.net.URI.create("https://example.test/image.png"))), List.of());
        var input = ModelInputAccounting.admissionEstimate(
                model, List.of(protectedMessage, media), List.of(), Map.of("type", "object"));
        assertThat(input.multimodalReserve())
                .isEqualTo(Math.max(
                        model.maxOutputTokens(),
                        io.haifa.agent.context.budget.ContextWindowBudget.safetyMargin(model.contextWindow(), false)));
        assertThat(input.continuationReserve())
                .isEqualTo(protectedMessage.reasoning().orElseThrow().byteLength());
        assertThat(input.totalTokens())
                .isEqualTo(input.visibleTokens() + input.multimodalReserve() + input.continuationReserve());
        assertThat(input.toString()).doesNotContain("protected");
        assertThat(RuntimeControlOptions.unifiedContextBudget(Map.of(RuntimeControlOptions.WORKING_SET, true)))
                .isFalse();
        assertThat(RuntimeControlOptions.unifiedContextBudget(Map.of(
                        RuntimeControlOptions.WORKING_SET,
                        true,
                        RuntimeControlOptions.CONTEXT_BUDGET_POLICY,
                        RuntimeControlOptions.UNIFIED_CONTEXT_V1)))
                .isTrue();
        assertThat(RuntimeControlOptions.providerOptions(
                        Map.of(RuntimeControlOptions.CONTEXT_BUDGET_POLICY, RuntimeControlOptions.UNIFIED_CONTEXT_V1)))
                .isEmpty();
    }

    @Test
    void exactPriceEvidenceIsValidatedAndNeverForwarded() {
        var prices = Map.<String, Object>of(
                "modelConfigurationDigest",
                "model-digest",
                "source",
                "https://example.test/prices",
                "date",
                "2026-11-04",
                "uncached",
                1,
                "cacheRead",
                0.1,
                "cacheWrite",
                1.25);
        var options = Map.<String, Object>of(RuntimeControlOptions.INPUT_PRICE_RATIOS, prices);
        assertThat(RuntimeControlOptions.inputPriceRatios(options)).isPresent();
        assertThat(RuntimeControlOptions.providerOptions(options)).isEmpty();
        assertThatThrownBy(() -> RuntimeControlOptions.inputPriceRatios(
                        Map.of(RuntimeControlOptions.INPUT_PRICE_RATIOS, Map.of("cacheRead", 0))))
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
