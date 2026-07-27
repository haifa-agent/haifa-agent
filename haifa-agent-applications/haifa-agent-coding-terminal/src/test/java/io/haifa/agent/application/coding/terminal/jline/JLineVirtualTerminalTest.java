package io.haifa.agent.application.coding.terminal.jline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.coding.terminal.view.TerminalRenderer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
            lifecycle.enterRawMode();
            lifecycle.enterFullScreen();
            JLineDisplayAdapter display = new JLineDisplayAdapter(terminal);
            display.render(new TerminalRenderer().render(TerminalUiState.initial(120, 40)));
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertThat(rendered).contains("\033[?1049h").contains("\033[?1049l");
    }

    @Test
    void editsAndSubmitsThroughTheSingleNonBlockingInputOwner() throws Exception {
        Terminal terminal = virtualTerminal("ab\u007fc\033[Dd\r");

        List<TerminalInput> inputs = new ArrayList<>();
        String buffer = "";
        int cursor = 0;
        try (JLineTerminalLifecycle lifecycle = JLineTerminalLifecycle.forTerminal(terminal)) {
            lifecycle.enterRawMode();
            JLineEditor editor = new JLineEditor(terminal, List::of);
            for (int index = 0; index < 16; index++) {
                TerminalInput input = editor.read(buffer, cursor, false);
                inputs.add(input);
                if (input.kind() == TerminalInput.Kind.EDITOR_CHANGED) {
                    buffer = input.text();
                    cursor = input.cursor();
                }
                if (input.kind() == TerminalInput.Kind.SUBMIT) {
                    break;
                }
            }
        }

        assertThat(inputs.get(inputs.size() - 1)).isEqualTo(new TerminalInput(TerminalInput.Kind.SUBMIT, "adc"));
        assertThat(inputs)
                .anyMatch(input -> input.kind() == TerminalInput.Kind.EDITOR_CHANGED
                        && input.text().equals("ac")
                        && input.cursor() == 1);
    }

    @Test
    void mapsSelectorAndControlKeysWithoutStartingACompetingLineReaderDisplay() throws Exception {
        Terminal terminal = virtualTerminal("\033[A\033[B\017\033\r\033[1;3A\033[13;2u\033\004");

        List<TerminalInput.Kind> kinds = new ArrayList<>();
        try (JLineTerminalLifecycle lifecycle = JLineTerminalLifecycle.forTerminal(terminal)) {
            lifecycle.enterRawMode();
            JLineEditor editor = new JLineEditor(terminal, List::of);
            for (int index = 0; index < 8; index++) {
                kinds.add(editor.read("", 0, true).kind());
            }
        }

        assertThat(kinds)
                .containsExactly(
                        TerminalInput.Kind.SELECT_PREVIOUS,
                        TerminalInput.Kind.SELECT_NEXT,
                        TerminalInput.Kind.TOGGLE_EXPANSION,
                        TerminalInput.Kind.FOLLOW_UP,
                        TerminalInput.Kind.RESTORE,
                        TerminalInput.Kind.EDITOR_CHANGED,
                        TerminalInput.Kind.CANCEL_OR_CLOSE,
                        TerminalInput.Kind.EOF);
    }

    @Test
    void mapsABareEscapeToTheGlobalCancelAction() throws Exception {
        Terminal terminal = virtualTerminal("\033");

        TerminalInput input;
        try (JLineTerminalLifecycle lifecycle = JLineTerminalLifecycle.forTerminal(terminal)) {
            lifecycle.enterRawMode();
            input = new JLineEditor(terminal, List::of).read("draft", 5, false);
        }

        assertThat(input).isEqualTo(new TerminalInput(TerminalInput.Kind.CANCEL_OR_CLOSE, "draft", 5));
    }

    @Test
    void tabRequestsVisibleCompletionWithoutMutatingTheEditorBuffer() throws Exception {
        Terminal terminal = virtualTerminal("/\t");
        TerminalInput input = null;
        String buffer = "";
        int cursor = 0;

        try (JLineTerminalLifecycle lifecycle = JLineTerminalLifecycle.forTerminal(terminal)) {
            lifecycle.enterRawMode();
            JLineEditor editor = new JLineEditor(terminal, List::of);
            for (int index = 0; index < 4; index++) {
                input = editor.read(buffer, cursor, false);
                if (input.kind() == TerminalInput.Kind.EDITOR_CHANGED) {
                    buffer = input.text();
                    cursor = input.cursor();
                }
                if (input.kind() == TerminalInput.Kind.COMPLETION_REQUESTED) {
                    break;
                }
            }
        }

        assertThat(input).isEqualTo(new TerminalInput(TerminalInput.Kind.COMPLETION_REQUESTED, "/", 1));
    }

    @Test
    void routesTerminalInterruptSignalToTheApplicationOwner() throws Exception {
        Terminal terminal = virtualTerminal("");
        AtomicBoolean interrupted = new AtomicBoolean();

        try (JLineTerminalLifecycle lifecycle = JLineTerminalLifecycle.forTerminal(terminal)) {
            lifecycle.installSignalHandlers(ignored -> {}, () -> interrupted.set(true));
            terminal.raise(Terminal.Signal.INT);
        }

        assertThat(interrupted).isTrue();
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

    private static Terminal virtualTerminal(String input) throws Exception {
        return TerminalBuilder.builder()
                .system(false)
                .streams(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream())
                .type("xterm-256color")
                .size(new Size(120, 40))
                .build();
    }
}
