package io.haifa.agent.runtime.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class ReasoningBudgetTest {
    private static final Instant START = Instant.parse("2026-09-14T00:00:00Z");

    @Test
    void countsExactUtf8BytesAndStopsOnlyAfterTheConfiguredLimit() {
        ReasoningBudget budget = new ReasoningBudget(OptionalLong.of(4), OptionalLong.empty());

        assertThat(budget.observe("a", START)).isTrue();
        assertThat(budget.observe("你", START)).isTrue();
        assertThat(budget.observe("b", START)).isFalse();

        assertThat(budget.bytes()).isEqualTo(5);
        assertThat(budget.events()).isEqualTo(3);
        assertThat(budget.exceeded()).isTrue();
    }

    @Test
    void measuresDurationFromTheFirstNonEmptyReasoningDelta() {
        ReasoningBudget budget = new ReasoningBudget(OptionalLong.empty(), OptionalLong.of(300_000));

        assertThat(budget.observe("first", START)).isTrue();
        assertThat(budget.observe("at-limit", START.plusMillis(300_000))).isTrue();
        assertThat(budget.observe("over-limit", START.plusMillis(300_001))).isFalse();

        assertThat(budget.durationMillis()).isEqualTo(300_001);
        assertThat(budget.events()).isEqualTo(3);
    }
}
