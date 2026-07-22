package io.haifa.agent.execution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionCommandTest {
    @Test
    void keepsDirectArgvAndShellTextAsDistinctCommandForms() {
        ExecutionCommand direct = ExecutionCommand.direct(List.of("java", "-version"));
        ExecutionCommand shell = ExecutionCommand.shell("printf 'ok\\n' | sed 's/ok/done/'");

        assertThat(direct.mode()).isEqualTo(ExecutionCommandMode.DIRECT);
        assertThat(direct.argv()).containsExactly("java", "-version");
        assertThat(direct.executable()).isEqualTo("java");
        assertThat(shell.mode()).isEqualTo(ExecutionCommandMode.SHELL);
        assertThat(shell.shellCommand()).contains("| sed");
        assertThat(shell).isEqualTo(ExecutionCommand.shell("printf 'ok\\n' | sed 's/ok/done/'"));
        assertThat(shell).isNotEqualTo(ExecutionCommand.shell("printf 'different\\n'"));
        assertThat(shell).isNotEqualTo(direct);
        assertThatThrownBy(shell::argv).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(shell::executable).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(direct::shellCommand).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInvalidShellTextAndLegacyShellArgvConstruction() {
        assertThatThrownBy(() -> ExecutionCommand.shell("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionCommand.shell("bad\0command")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionCommand.shell("x".repeat(32 * 1024 + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionCommand(ExecutionCommandMode.SHELL, List.of("echo ok")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ExecutionCommand.shell");
    }
}
