package io.haifa.agent.runtime.api.display;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ToolDisplayBudgetTest {

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> new ToolDisplayBudget(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDisplayBudget(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDisplayBudget(1, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
