package io.haifa.agent.runtime.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ToolPipelineDeadlineTest {
    @Test
    void capsToolWindowToRunRemainingWallTime() {
        assertThat(ToolPipeline.invocationWindow(Duration.ofMinutes(30), 60_000, 57_000))
                .isEqualTo(Duration.ofSeconds(3));
        assertThat(ToolPipeline.invocationWindow(Duration.ofSeconds(2), 60_000, 10_000))
                .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void rejectsInvocationAfterRunWallTimeIsConsumed() {
        assertThatThrownBy(() -> ToolPipeline.invocationWindow(Duration.ofMinutes(30), 60_000, 60_000))
                .isInstanceOf(RuntimeLimitExceededException.class)
                .hasMessageContaining("wallTimeMillis");
    }
}
