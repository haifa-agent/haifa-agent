package io.haifa.agent.runtime.api.display;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ToolDisplayBudgetTest {

    @Test
    void twoArgumentBudgetAppliesDefaultReferenceCap() {
        ToolDisplayBudget budget = new ToolDisplayBudget(1_024, 100);

        assertThat(ToolDisplayBudget.DEFAULT_MAX_REFERENCES).isEqualTo(32);
        assertThat(budget.maxReferences()).isEqualTo(ToolDisplayBudget.DEFAULT_MAX_REFERENCES);
    }

    @Test
    void keepsExplicitReferenceCap() {
        assertThat(new ToolDisplayBudget(1_024, 100, 5).maxReferences()).isEqualTo(5);
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> new ToolDisplayBudget(0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDisplayBudget(1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDisplayBudget(1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDisplayBudget(1, 1, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
