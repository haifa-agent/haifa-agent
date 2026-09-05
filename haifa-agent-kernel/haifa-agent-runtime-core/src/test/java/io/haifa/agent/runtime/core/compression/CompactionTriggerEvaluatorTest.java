package io.haifa.agent.runtime.core.compression;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.context.compression.CompressionPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompactionTriggerEvaluatorTest {

    @Test
    @DisplayName("calculateBreakdown correctly bounds headroom and computes soft limit")
    void testCalculateBreakdown() {
        CompressionPolicy policy = CompressionPolicy.defaults().withSemanticCompactionEnabled(true);
        CompactionTriggerEvaluator evaluator = new CompactionTriggerEvaluator(policy);

        long contextWindow = 128_000L;
        long outputReserve = 4_096L;
        long fixedPrefix = 2_000L;
        long otherSources = 1_000L;
        long currentTokens = 50_000L;

        ContextBudgetBreakdown breakdown = evaluator.calculateBreakdown(
                contextWindow, outputReserve, fixedPrefix, otherSources, currentTokens);

        long expectedSafety = 128_000L * 5 / 100L; // 6,400
        long expectedAvailable = 128_000L - 4_096L - expectedSafety - 2_000L - 1_000L; // 114,504
        long calculatedHeadroom = (expectedAvailable * policy.softTriggerHeadroomPercent()) / 100L;
        long expectedHeadroom = Math.clamp(calculatedHeadroom, (long) policy.minTriggerHeadroom(), (long) policy.maxTriggerHeadroom());
        long expectedSoftLimit = expectedAvailable - expectedHeadroom;

        assertThat(breakdown.contextWindowTokens()).isEqualTo(contextWindow);
        assertThat(breakdown.outputReserveTokens()).isEqualTo(outputReserve);
        assertThat(breakdown.safetyMarginTokens()).isEqualTo(expectedSafety);
        assertThat(breakdown.availableSessionTokens()).isEqualTo(expectedAvailable);
        assertThat(breakdown.triggerHeadroomTokens()).isEqualTo(expectedHeadroom);
        assertThat(breakdown.softLimitTokens()).isEqualTo(expectedSoftLimit);
        assertThat(breakdown.currentSessionTokens()).isEqualTo(currentTokens);
    }

    @Test
    @DisplayName("evaluate does not trigger compaction when disabled by policy")
    void testDisabledPolicy() {
        CompressionPolicy policy = CompressionPolicy.defaults(); // semanticCompactionEnabled = false
        CompactionTriggerEvaluator evaluator = new CompactionTriggerEvaluator(policy);

        CompactionTriggerDecision decision = evaluator.evaluate(
                128_000L, 4_096L, 2_000L, 1_000L, 120_000L, 10);

        assertThat(decision.shouldCompact()).isFalse();
        assertThat(decision.reason()).isEqualTo(CompactionTriggerReason.NONE);
    }

    @Test
    @DisplayName("evaluate does not trigger compaction when current tokens are below soft limit")
    void testBelowSoftLimit() {
        CompressionPolicy policy = CompressionPolicy.defaults().withSemanticCompactionEnabled(true);
        CompactionTriggerEvaluator evaluator = new CompactionTriggerEvaluator(policy);

        ContextBudgetBreakdown breakdown = evaluator.calculateBreakdown(
                128_000L, 4_096L, 2_000L, 1_000L, 20_000L);

        CompactionTriggerDecision decision = evaluator.evaluate(
                128_000L, 4_096L, 2_000L, 1_000L, 20_000L, 10);

        assertThat(20_000L).isLessThan(breakdown.softLimitTokens());
        assertThat(decision.shouldCompact()).isFalse();
        assertThat(decision.reason()).isEqualTo(CompactionTriggerReason.NONE);
    }

    @Test
    @DisplayName("evaluate triggers compaction when tokens exceed soft limit and turn count >= 2")
    void testExceedsSoftLimitWithSufficientTurns() {
        CompressionPolicy policy = CompressionPolicy.defaults().withSemanticCompactionEnabled(true);
        CompactionTriggerEvaluator evaluator = new CompactionTriggerEvaluator(policy);

        ContextBudgetBreakdown breakdown = evaluator.calculateBreakdown(
                128_000L, 4_096L, 2_000L, 1_000L, 110_000L);

        CompactionTriggerDecision decision = evaluator.evaluate(
                128_000L, 4_096L, 2_000L, 1_000L, 110_000L, 5);

        assertThat(110_000L).isGreaterThanOrEqualTo(breakdown.softLimitTokens());
        assertThat(decision.shouldCompact()).isTrue();
        assertThat(decision.reason()).isEqualTo(CompactionTriggerReason.SOFT_TOKEN_THRESHOLD);
    }

    @Test
    @DisplayName("evaluate does not trigger compaction when turn count < 2 even if token threshold exceeded")
    void testInsufficientTurns() {
        CompressionPolicy policy = CompressionPolicy.defaults().withSemanticCompactionEnabled(true);
        CompactionTriggerEvaluator evaluator = new CompactionTriggerEvaluator(policy);

        CompactionTriggerDecision decision = evaluator.evaluate(
                128_000L, 4_096L, 2_000L, 1_000L, 110_000L, 1);

        assertThat(decision.shouldCompact()).isFalse();
        assertThat(decision.reason()).isEqualTo(CompactionTriggerReason.NONE);
    }
}
