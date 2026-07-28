package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Tui4jTerminalIoTest {
    @Test
    void failsClosedForNonInteractiveAndDumbTerminals() {
        var nonInteractive = new Tui4jTerminalIo(Optional.empty(), Optional.empty(), List.of(), false, false);
        var dumb = new Tui4jTerminalIo(Optional.empty(), Optional.empty(), List.of("TERM=dumb"), false, true);

        assertThatThrownBy(nonInteractive::requireInteractive)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(Tui4jCodingTerminal.TUI_UNAVAILABLE);
        assertThatThrownBy(dumb::requireInteractive)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(Tui4jCodingTerminal.TUI_UNAVAILABLE);
    }

    @Test
    void reportsActionableModifiedEnterCompatibilityNoticesWithoutWritingConfiguration() {
        assertThat(io("WT_SESSION=present").compatibilityNotice()).contains("WINDOWS_TERMINAL_MODIFIED_ENTER_REMAP");
        assertThat(io("TERM_PROGRAM=WezTerm").compatibilityNotice()).contains("WEZTERM_OPTION_ENTER_REMAP");
        assertThat(io("TERM_PROGRAM=Alacritty").compatibilityNotice()).contains("ALACRITTY_OPTION_ENTER_REMAP");
        assertThat(io("TERM_PROGRAM=Apple_Terminal").compatibilityNotice())
                .contains("APPLE_TERMINAL_MODIFIED_ENTER_LIMITED");
        assertThat(io("TERM_PROGRAM=iTerm.app").compatibilityNotice()).isEmpty();
    }

    private Tui4jTerminalIo io(String environment) {
        return new Tui4jTerminalIo(Optional.empty(), Optional.empty(), List.of(environment), false, true);
    }
}
