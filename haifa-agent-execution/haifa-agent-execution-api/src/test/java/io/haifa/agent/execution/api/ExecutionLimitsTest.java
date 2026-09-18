package io.haifa.agent.execution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ExecutionLimitsTest {
    @Test
    void acceptsTheTwoHourSharedCeilingAndRejectsAnythingAboveIt() {
        assertThat(new ExecutionLimits(ExecutionLimits.MAXIMUM_ALLOWED_TIMEOUT, 1024, 1024).timeout())
                .isEqualTo(Duration.ofHours(2));
        assertThatThrownBy(() -> new ExecutionLimits(Duration.ofHours(2).plusMillis(1), 1024, 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout is out of range");
    }
}
