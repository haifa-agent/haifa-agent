package io.haifa.agent.runtime.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.model.api.ModelErrorCategory;
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
        assertThat(budget.limitFailure()).isEqualTo(ModelErrorCategory.REASONING_BYTE_LIMIT_EXCEEDED);
    }

    @Test
    void aDeltaThatBreachesBothLimitsIsClassifiedAsAByteBreach() {
        ReasoningBudget budget = new ReasoningBudget(OptionalLong.of(1), OptionalLong.of(1));

        assertThat(budget.observe("a", START)).isTrue();
        assertThat(budget.observe("b", START.plusMillis(2))).isFalse();

        assertThat(budget.limitFailure()).isEqualTo(ModelErrorCategory.REASONING_BYTE_LIMIT_EXCEEDED);
    }

    @Test
    void measuresDurationFromTheFirstNonEmptyReasoningDelta() {
        ReasoningBudget budget = new ReasoningBudget(OptionalLong.empty(), OptionalLong.of(300_000));

        assertThat(budget.observe("first", START)).isTrue();
        assertThat(budget.observe("at-limit", START.plusMillis(300_000))).isTrue();
        assertThat(budget.observe("over-limit", START.plusMillis(300_001))).isFalse();

        assertThat(budget.limitFailure()).isEqualTo(ModelErrorCategory.REASONING_TIME_LIMIT_EXCEEDED);
        assertThat(budget.durationMillis()).isEqualTo(300_001);
        assertThat(budget.events()).isEqualTo(3);
    }
}
