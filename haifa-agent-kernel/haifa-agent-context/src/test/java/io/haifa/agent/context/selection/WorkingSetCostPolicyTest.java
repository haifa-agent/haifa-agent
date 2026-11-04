package io.haifa.agent.context.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkingSetCostPolicyTest {
    private static final WorkingSetCostPolicy.Prices PRICES =
            new WorkingSetCostPolicy.Prices(new BigDecimal("0.1"), new BigDecimal("1.25"), BigDecimal.ONE);

    @Test
    void frozenMs1ThreeLaneOraclesChooseBothActualBranches() {
        var reselect = WorkingSetCostPolicy.decide(
                new WorkingSetCostPolicy.Lanes(90000, 0, 5000),
                new WorkingSetCostPolicy.Lanes(500, 2000, 500),
                Optional.of(PRICES),
                1,
                true,
                true,
                95000,
                100000,
                0,
                2);
        assertThat(reselect.defer()).isFalse();
        assertThat(reselect.reason()).isEqualTo("RESELECT_CHEAPER");
        assertThat(reselect.retainCost().orElseThrow()).isEqualByComparingTo("14000");
        assertThat(reselect.reselectCost().orElseThrow()).isEqualByComparingTo("3050");
        var defer = decision(true, true, 5500, 20000, 0, Optional.of(PRICES));
        assertThat(defer.defer()).isTrue();
        assertThat(defer.retainCost().orElseThrow()).isEqualByComparingTo("1000");
        assertThat(defer.reselectCost().orElseThrow()).isEqualByComparingTo("3010");
    }

    @Test
    void boundedDeferralExitsForPrefixTtlSourceQualityCapacityAndCallLimits() {
        assertThat(decision(false, true, 5500, 20000, 0, Optional.of(PRICES)).reason())
                .isEqualTo("PREFIX_SOURCE_OR_TTL_MISMATCH");
        assertThat(decision(true, false, 5500, 20000, 0, Optional.of(PRICES)).reason())
                .isEqualTo("QUALITY_OR_GOAL_CHANGED");
        assertThat(decision(true, true, 20000, 20000, 0, Optional.of(PRICES)).reason())
                .isEqualTo("RETENTION_LIMIT");
        assertThat(decision(true, true, 19999, 20000, 2, Optional.of(PRICES)).reason())
                .isEqualTo("DEFERRED_CALL_LIMIT");
        var unavailable = decision(true, true, 5500, 20000, 0, Optional.empty());
        assertThat(unavailable.defer()).isFalse();
        assertThat(unavailable.retainCost()).isEmpty();
        assertThat(unavailable.reselectCost()).isEmpty();
    }

    @Test
    void writesOccurOnceAndLaterCallsReadTheNewPrefixWithoutIntegerOverflow() {
        assertThat(WorkingSetCostPolicy.cost(new WorkingSetCostPolicy.Lanes(100, 2000, 500), PRICES, 3))
                .isEqualByComparingTo("4430");
        assertThat(WorkingSetCostPolicy.cost(new WorkingSetCostPolicy.Lanes(Long.MAX_VALUE, 0, 0), PRICES, 3))
                .isPositive();
        assertThatThrownBy(() -> new WorkingSetCostPolicy.Lanes(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static WorkingSetCostPolicy.Decision decision(
            boolean reusable,
            boolean quality,
            long tokens,
            long limit,
            int calls,
            Optional<WorkingSetCostPolicy.Prices> prices) {
        return WorkingSetCostPolicy.decide(
                new WorkingSetCostPolicy.Lanes(5000, 0, 500),
                new WorkingSetCostPolicy.Lanes(100, 2000, 500),
                prices,
                1,
                reusable,
                quality,
                tokens,
                limit,
                calls,
                2);
    }
}
