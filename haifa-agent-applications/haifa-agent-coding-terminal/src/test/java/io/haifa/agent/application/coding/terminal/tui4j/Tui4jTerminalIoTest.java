package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.williamcallahan.tui4j.compat.bubbletea.QuitMessage;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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

    @Test
    void productionProgramEnablesWheelEventsAndRestoresMouseModesOnExit() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var inputWriter = new PipedOutputStream();
                var input = new PipedInputStream(inputWriter)) {
            var terminalIo = Tui4jTerminalIo.streams(input, output, List.of("TERM=xterm-256color"));
            var program = terminalIo.program(new Tui4jTerminalSpikeModel(80, 24));
            CompletableFuture<Void> run = CompletableFuture.runAsync(program::run)
                    .orTimeout(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);

            program.waitForInit();
            program.send(new QuitMessage());
            run.get(10, TimeUnit.SECONDS);
        }

        assertThat(output.toString(Charset.defaultCharset()))
                .contains("\u001B[?1002h", "\u001B[?1006h")
                .contains("\u001B[?1002l", "\u001B[?1006l");
    }

    private Tui4jTerminalIo io(String environment) {
        return new Tui4jTerminalIo(Optional.empty(), Optional.empty(), List.of(environment), false, true);
    }
}
