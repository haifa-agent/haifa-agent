package io.haifa.agent.application.coding.terminal.jline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.coding.terminal.view.TerminalRenderer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

class JLineVirtualTerminalTest {
    @Test
    void rendersThroughDifferentialDisplayAndRestoresVirtualTerminal() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder()
                .system(false)
                .streams(new ByteArrayInputStream(new byte[0]), output)
                .type("xterm-256color")
                .size(new Size(120, 40))
                .build();

        try (JLineTerminalLifecycle lifecycle = JLineTerminalLifecycle.forTerminal(terminal)) {
            JLineDisplayAdapter display = new JLineDisplayAdapter(terminal);
            display.render(new TerminalRenderer().render(TerminalUiState.initial(120, 40)));
        }

        assertThat(output.toString(StandardCharsets.UTF_8)).isNotEmpty();
    }

    @Test
    void rejectsDisplayAccessFromASecondThread() throws Exception {
        Terminal terminal = TerminalBuilder.builder()
                .system(false)
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .type("xterm")
                .size(new Size(80, 24))
                .build();
        JLineDisplayAdapter display = new JLineDisplayAdapter(terminal);
        var view = new TerminalRenderer().render(TerminalUiState.initial(80, 24));
        display.render(view);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                display.render(view);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        thread.start();
        thread.join();
        terminal.close();

        assertThatThrownBy(() -> {
                    if (failure.get() != null) {
                        throw failure.get();
                    }
                })
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TERMINAL_UI_THREAD_VIOLATION");
    }
}
