package io.haifa.agent.runtime.core.compaction;

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

        ContextBudgetBreakdown breakdown =
                evaluator.calculateBreakdown(contextWindow, outputReserve, fixedPrefix, otherSources, currentTokens);

        long expectedSafety = 128_000L * 5 / 100L; // 6,400
        long expectedAvailable = 128_000L - 4_096L - expectedSafety - 2_000L - 1_000L; // 114,504
        long calculatedHeadroom = (expectedAvailable * policy.softTriggerHeadroomPercent()) / 100L;
        long expectedHeadroom =
                Math.clamp(calculatedHeadroom, (long) policy.minTriggerHeadroom(), (long) policy.maxTriggerHeadroom());
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
        CompressionPolicy policy = CompressionPolicy.defaults().withSemanticCompactionEnabled(false);
        CompactionTriggerEvaluator evaluator = new CompactionTriggerEvaluator(policy);

        CompactionTriggerDecision decision = evaluator.evaluate(128_000L, 4_096L, 2_000L, 1_000L, 120_000L, 10);

        assertThat(decision.shouldCompact()).isFalse();
        assertThat(decision.reason()).isEqualTo(CompactionTriggerReason.NONE);
    }

    @Test
    @DisplayName("evaluate does not trigger compaction when current tokens are below soft limit")
    void testBelowSoftLimit() {
        CompressionPolicy policy = CompressionPolicy.defaults().withSemanticCompactionEnabled(true);
        CompactionTriggerEvaluator evaluator = new CompactionTriggerEvaluator(policy);

        ContextBudgetBreakdown breakdown = evaluator.calculateBreakdown(128_000L, 4_096L, 2_000L, 1_000L, 20_000L);

        CompactionTriggerDecision decision = evaluator.evaluate(128_000L, 4_096L, 2_000L, 1_000L, 20_000L, 10);

        assertThat(20_000L).isLessThan(breakdown.softLimitTokens());
        assertThat(decision.shouldCompact()).isFalse();
        assertThat(decision.reason()).isEqualTo(CompactionTriggerReason.NONE);
    }

    @Test
    @DisplayName("evaluate triggers compaction when tokens exceed soft limit and turn count >= 2")
    void testExceedsSoftLimitWithSufficientTurns() {
        CompressionPolicy policy = CompressionPolicy.defaults().withSemanticCompactionEnabled(true);
        CompactionTriggerEvaluator evaluator = new CompactionTriggerEvaluator(policy);

        ContextBudgetBreakdown breakdown = evaluator.calculateBreakdown(128_000L, 4_096L, 2_000L, 1_000L, 110_000L);

        CompactionTriggerDecision decision = evaluator.evaluate(128_000L, 4_096L, 2_000L, 1_000L, 110_000L, 5);

        assertThat(110_000L).isGreaterThanOrEqualTo(breakdown.softLimitTokens());
        assertThat(decision.shouldCompact()).isTrue();
        assertThat(decision.reason()).isEqualTo(CompactionTriggerReason.SOFT_TOKEN_THRESHOLD);
    }

    @Test
    @DisplayName("evaluate does not trigger compaction when turn count < 2 even if token threshold exceeded")
    void testInsufficientTurns() {
        CompressionPolicy policy = CompressionPolicy.defaults().withSemanticCompactionEnabled(true);
        CompactionTriggerEvaluator evaluator = new CompactionTriggerEvaluator(policy);

        CompactionTriggerDecision decision = evaluator.evaluate(128_000L, 4_096L, 2_000L, 1_000L, 110_000L, 1);

        assertThat(decision.shouldCompact()).isFalse();
        assertThat(decision.reason()).isEqualTo(CompactionTriggerReason.NONE);
    }

    @Test
    @DisplayName(
            "evaluate triggers ACTIVE_HISTORY_BUDGET when tokens exceed activeHistoryBudgetTokens but below capacity limit")
    void testActiveHistoryBudgetTrigger() {
        long budget = 96_000L;
        CompressionPolicy policy =
                CompressionPolicy.defaults().withSemanticCompactionEnabled(true).withActiveHistoryBudgetTokens(budget);
        CompactionTriggerEvaluator evaluator = new CompactionTriggerEvaluator(policy);

        ContextBudgetBreakdown breakdown = evaluator.calculateBreakdown(200_000L, 4_096L, 2_000L, 1_000L, 97_000L);
        assertThat(breakdown.softLimitTokens()).isEqualTo(budget);

        // Under budget: should not compact
        CompactionTriggerDecision underBudget = evaluator.evaluate(200_000L, 4_096L, 2_000L, 1_000L, 90_000L, 5);
        assertThat(underBudget.shouldCompact()).isFalse();
        assertThat(underBudget.reason()).isEqualTo(CompactionTriggerReason.NONE);

        // Over budget, below capacity soft limit: should compact with ACTIVE_HISTORY_BUDGET
        CompactionTriggerDecision overBudget = evaluator.evaluate(200_000L, 4_096L, 2_000L, 1_000L, 97_000L, 5);
        assertThat(overBudget.shouldCompact()).isTrue();
        assertThat(overBudget.reason()).isEqualTo(CompactionTriggerReason.ACTIVE_HISTORY_BUDGET);

        // Over capacity soft limit: should compact with SOFT_TOKEN_THRESHOLD
        CompactionTriggerDecision overCapacity = evaluator.evaluate(200_000L, 4_096L, 2_000L, 1_000L, 175_000L, 5);
        assertThat(overCapacity.shouldCompact()).isTrue();
        assertThat(overCapacity.reason()).isEqualTo(CompactionTriggerReason.SOFT_TOKEN_THRESHOLD);
    }

    @Test
    @DisplayName("Dynamic budget and tail scale with context window (unbounded upper limit)")
    void dynamicBudgetAndTailScaleWithContextWindow() {
        CompressionPolicy policy = CompressionPolicy.defaults()
                .withDynamicActiveBudget(50, 64_000L, Long.MAX_VALUE)
                .withTailTokenBounds(24_000, Integer.MAX_VALUE)
                .withTargetTailTokenPercent(30);
        CompactionTriggerEvaluator evaluator = new CompactionTriggerEvaluator(policy);

        long outputReserve = 4_096L;
        long fixedPrefix = 2_000L;
        long otherSources = 1_000L;

        // 128k context: 50% available is ~57k, clamped to min 64k
        ContextBudgetBreakdown b128k =
                evaluator.calculateBreakdown(128_000L, outputReserve, fixedPrefix, otherSources, 0L);
        assertThat(b128k.resolvedActiveHistoryBudgetTokens()).isEqualTo(64_000L);
        assertThat(b128k.resolvedRetainedTailTokens()).isEqualTo(24_000L); // 64k * 30% = 19.2k, min clamp 24k

        // 192k context: available 175,304 -> 50% = 87,652
        ContextBudgetBreakdown b192k =
                evaluator.calculateBreakdown(192_000L, outputReserve, fixedPrefix, otherSources, 0L);
        assertThat(b192k.resolvedActiveHistoryBudgetTokens()).isEqualTo(87_652L);
        assertThat(b192k.resolvedRetainedTailTokens()).isEqualTo(26_295L); // 87,652 * 30%

        // 256k context: available 236,104 -> 50% = 118,052
        ContextBudgetBreakdown b256k =
                evaluator.calculateBreakdown(256_000L, outputReserve, fixedPrefix, otherSources, 0L);
        assertThat(b256k.resolvedActiveHistoryBudgetTokens()).isEqualTo(118_052L);
        assertThat(b256k.resolvedRetainedTailTokens()).isEqualTo(35_415L); // 118,052 * 30%

        // 640k context: available 616,520 -> 50% = 308,260
        ContextBudgetBreakdown b640k =
                evaluator.calculateBreakdown(640_000L, outputReserve, fixedPrefix, otherSources, 0L);
        assertThat(b640k.resolvedActiveHistoryBudgetTokens()).isEqualTo(308_260L);
        assertThat(b640k.resolvedRetainedTailTokens()).isEqualTo(92_478L); // 308,260 * 30%

        // 1M context: available 976,520 -> 50% = 488,260
        ContextBudgetBreakdown b1m =
                evaluator.calculateBreakdown(1_000_000L, outputReserve, fixedPrefix, otherSources, 0L);
        assertThat(b1m.resolvedActiveHistoryBudgetTokens()).isEqualTo(488_260L);
        assertThat(b1m.resolvedRetainedTailTokens()).isEqualTo(146_478L); // 488,260 * 30%
        assertThat(b1m.resolvedRetainedTailTokens()).isLessThanOrEqualTo(b1m.resolvedActiveHistoryBudgetTokens());
    }

    @Test
    @DisplayName("Bounded dynamic budget clamps between min and max tokens, tail clamps within bounds")
    void boundedDynamicBudgetAndTailMatrix() {
        CompressionPolicy policy = CompressionPolicy.defaults()
                .withDynamicActiveBudget(25, 48_000L, 96_000L)
                .withTailTokenBounds(24_000, 32_000)
                .withTargetTailTokenPercent(40);
        CompactionTriggerEvaluator evaluator = new CompactionTriggerEvaluator(policy);

        long outputReserve = 4_096L;
        long fixedPrefix = 2_000L;
        long otherSources = 1_000L;

        // 128k context: 25% available is ~28.6k, clamped to min 48k
        ContextBudgetBreakdown b128k =
                evaluator.calculateBreakdown(128_000L, outputReserve, fixedPrefix, otherSources, 0L);
        assertThat(b128k.resolvedActiveHistoryBudgetTokens()).isEqualTo(48_000L);
        assertThat(b128k.resolvedRetainedTailTokens()).isEqualTo(24_000L); // 48k * 40% = 19.2k, clamped to min 24k

        // 256k context: 25% available 236,104 = 59,026
        ContextBudgetBreakdown b256k =
                evaluator.calculateBreakdown(256_000L, outputReserve, fixedPrefix, otherSources, 0L);
        assertThat(b256k.resolvedActiveHistoryBudgetTokens()).isEqualTo(59_026L);
        assertThat(b256k.resolvedRetainedTailTokens()).isEqualTo(24_000L); // 59,026 * 40% = 23,610, clamped to min 24k

        // 400k context: 25% available 376,520 = 94,130
        ContextBudgetBreakdown b400k =
                evaluator.calculateBreakdown(400_000L, outputReserve, fixedPrefix, otherSources, 0L);
        assertThat(b400k.resolvedActiveHistoryBudgetTokens()).isEqualTo(94_130L);
        assertThat(b400k.resolvedRetainedTailTokens()).isEqualTo(32_000L); // 94,130 * 40% = 37,652, clamped to max 32k

        // 640k+ context: 25% available 616,520 = 154,130, clamped to max 96k
        ContextBudgetBreakdown b640k =
                evaluator.calculateBreakdown(640_000L, outputReserve, fixedPrefix, otherSources, 0L);
        assertThat(b640k.resolvedActiveHistoryBudgetTokens()).isEqualTo(96_000L);
        assertThat(b640k.resolvedRetainedTailTokens()).isEqualTo(32_000L); // 96k * 40% = 38.4k, clamped to max 32k
    }

    @Test
    @DisplayName("evaluate triggers ACTIVE_HISTORY_BUDGET when tokens exceed dynamic budget")
    void dynamicActiveBudgetTriggersCompaction() {
        CompressionPolicy policy = CompressionPolicy.defaults().withDynamicActiveBudget(50, 64_000L, Long.MAX_VALUE);
        CompactionTriggerEvaluator evaluator = new CompactionTriggerEvaluator(policy);

        // 256k context window -> budget is 118,052
        CompactionTriggerDecision underBudget = evaluator.evaluate(256_000L, 4_096L, 2_000L, 1_000L, 100_000L, 5);
        assertThat(underBudget.shouldCompact()).isFalse();

        CompactionTriggerDecision overBudget = evaluator.evaluate(256_000L, 4_096L, 2_000L, 1_000L, 120_000L, 5);
        assertThat(overBudget.shouldCompact()).isTrue();
        assertThat(overBudget.reason()).isEqualTo(CompactionTriggerReason.ACTIVE_HISTORY_BUDGET);
    }

    @Test
    @DisplayName("static activeHistoryBudgetTokens overrides dynamic ratio when explicitly set")
    void staticBudgetOverridesDynamicBudgetWhenPresent() {
        CompressionPolicy mixedPolicy = CompressionPolicy.defaults()
                .withDynamicActiveBudget(50, 64_000L, Long.MAX_VALUE)
                .withActiveHistoryBudgetTokens(75_000L);
        CompactionTriggerEvaluator evaluator = new CompactionTriggerEvaluator(mixedPolicy);

        ContextBudgetBreakdown breakdown = evaluator.calculateBreakdown(256_000L, 4_096L, 2_000L, 1_000L, 0L);
        assertThat(breakdown.resolvedActiveHistoryBudgetTokens()).isEqualTo(75_000L);
    }
}
