package io.haifa.agent.model.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class ModelUsageTest {
    @Test
    void exclusiveAndInclusiveInputReconcileAndHaveTheSameWeightedCost() {
        var exclusive = ModelUsage.exclusiveInput(OptionalLong.of(50), 7, OptionalLong.of(40), OptionalLong.of(10));
        var inclusive = ModelUsage.inclusiveInput(
                OptionalLong.of(100), 7, OptionalLong.of(40), OptionalLong.of(10), OptionalLong.empty(), 0, false, 0);
        assertThat(inclusive).isEqualTo(exclusive);
        assertThat(exclusive.inputTokens()).isEqualTo(100);
        assertThat(exclusive.cacheMissTokens()).isEqualTo(50);
        assertThat(exclusive.costWeightedInput(BigDecimal.ONE, new BigDecimal("0.1"), new BigDecimal("1.25")))
                .contains(new BigDecimal("66.50"));
    }

    @Test
    void missingFieldsAreUnknownWhileExplicitZeroRemainsKnown() {
        var missing = ModelUsage.inclusiveInput(
                OptionalLong.of(100), 7, OptionalLong.of(0), OptionalLong.empty(), OptionalLong.empty(), 0, false, 0);
        assertThat(missing.cacheMissTokensKnown()).isFalse();
        assertThat(missing.cacheWriteTokens()).isEmpty();
        assertThat(missing.costWeightedInput(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE))
                .isEmpty();
        var zero = ModelUsage.inclusiveInput(
                OptionalLong.of(0), 0, OptionalLong.of(0), OptionalLong.of(0), OptionalLong.empty(), 0, false, 0);
        assertThat(zero.cacheMissTokensKnown()).isTrue();
        assertThat(zero.cacheWriteTokens()).hasValue(0);
        assertThat(ModelUsage.unknown().inputTokensKnown()).isFalse();
        assertThat(new ModelUsage(1, 2, 0, 1, 0, false, 0).inputTokens()).isEqualTo(1);
        assertThatThrownBy(() -> ModelUsage.exclusiveInput(
                        OptionalLong.of(Long.MAX_VALUE), 0, OptionalLong.of(1), OptionalLong.of(0)))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> ModelUsage.inclusiveInput(
                        OptionalLong.of(10),
                        0,
                        OptionalLong.of(9),
                        OptionalLong.of(2),
                        OptionalLong.empty(),
                        0,
                        false,
                        0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
