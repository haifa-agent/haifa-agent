package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.williamcallahan.tui4j.ansi.TextWidth;
import com.williamcallahan.tui4j.compat.bubbles.textarea.Textarea;
import com.williamcallahan.tui4j.compat.bubbles.viewport.Viewport;
import com.williamcallahan.tui4j.compat.lipgloss.color.NoColor;
import com.williamcallahan.tui4j.term.TerminalInfo;
import io.haifa.agent.application.coding.terminal.state.TerminalFooter;
import io.haifa.agent.application.coding.terminal.state.TerminalSelector;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Tui4jTerminalViewTest {
    private final Tui4jTerminalView view = new Tui4jTerminalView();

    @BeforeAll
    static void configureHeadlessTerminalInfo() {
        TerminalInfo.provide(() -> new TerminalInfo(false, new NoColor()));
    }

    @Test
    void followsTheReviewedPrototypeRegionOrderAndFitsTheTerminalRows() {
        TerminalUiState state = TerminalUiState.initial(80, 24);
        Viewport transcript = transcript(state);

        String rendered = view.render(state, transcript, editor(80), true, false);

        assertThat(rendered)
                .containsSubsequence(
                        "HAIFA CODING AGENT",
                        "Startup help",
                        "Loaded resources",
                        "Diagnostics",
                        "Conversation",
                        "Pending messages",
                        "Status",
                        "Widgets above",
                        "Type a message",
                        "Widgets below",
                        "Footer");
        assertThat(rendered.lines()).hasSizeLessThanOrEqualTo(24);
    }

    @Test
    void keepsTheCompactLayoutAndSelectorInsideTheAvailableRows() {
        TerminalUiState initial = TerminalUiState.initial(60, 16);
        TerminalUiState state = new TerminalUiState(
                initial.header(),
                initial.loadedResources(),
                initial.transcript(),
                initial.pending(),
                initial.status(),
                "preserved draft",
                "preserved draft".length(),
                Optional.of(new TerminalSelector(
                        "completion",
                        "Commands",
                        List.of("/new", "/resume", "/rename", "/compact", "/reload", "/export"),
                        4)),
                initial.footer(),
                initial.columns(),
                initial.rows(),
                initial.session(),
                initial.currentRunId(),
                initial.appliedCursor(),
                initial.seenEventIds(),
                initial.recoverableError(),
                initial.exitRequested());
        Viewport transcript = transcript(state);

        String rendered = view.render(state, transcript, editor(60), true, false);

        assertThat(rendered)
                .containsSubsequence(
                        "Startup help",
                        "Loaded resources",
                        "Diagnostics",
                        "Conversation",
                        "Pending messages",
                        "Status",
                        "Widgets above",
                        "Commands",
                        "/reload",
                        "Widgets below",
                        "Footer");
        assertThat(rendered).contains("5-5 of 6", "editor preserved");
        assertThat(rendered.lines()).hasSizeLessThanOrEqualTo(16);
    }

    @Test
    void usesAStableDiagnosticBelowThePrototypeMinimumSize() {
        TerminalUiState state = TerminalUiState.initial(40, 10);

        assertThat(view.render(state, transcript(state), editor(40), true, false))
                .isEqualTo(
                        """
                        Haifa Coding Agent
                        Terminal is too small
                        Required: at least 60x16
                        Current: 40x10
                        Resize the terminal to continue.""");
    }

    @Test
    void clipsLongFixedRegionsSoTheyCannotPushTheFooterBeyondTheTerminalRows() {
        TerminalUiState initial = TerminalUiState.initial(120, 40);
        TerminalUiState state = new TerminalUiState(
                initial.header(),
                initial.loadedResources(),
                initial.transcript(),
                initial.pending(),
                initial.status(),
                initial.editorBuffer(),
                initial.editorCursor(),
                initial.selector(),
                new TerminalFooter(
                        "local-project-v1-" + "a".repeat(64),
                        "git: via safe read model",
                        "a long session name that must remain on one physical row",
                        "queue: 0",
                        "provider: frozen",
                        "model: frozen",
                        "COMPLETED",
                        "sandbox: frozen profile"),
                initial.columns(),
                initial.rows(),
                initial.session(),
                initial.currentRunId(),
                initial.appliedCursor(),
                initial.seenEventIds(),
                initial.recoverableError(),
                initial.exitRequested());

        String rendered = view.render(state, transcript(state), editor(120), true, false);

        assertThat(rendered.lines()).hasSizeLessThanOrEqualTo(40);
        assertThat(rendered.lines()).allMatch(line -> TextWidth.measureCellWidth(line) <= 119);
        assertThat(rendered).contains("COMPLETED");
    }

    @Test
    void aLongTranscriptStillProducesExactlyOneTerminalFrame() {
        TerminalUiState state = TerminalUiState.initial(120, 40);
        Viewport transcript = transcript(state);
        transcript.setContent(IntStream.rangeClosed(1, 40)
                .mapToObj(index -> "STUB-LONG-LINE-" + index)
                .collect(java.util.stream.Collectors.joining("\n")));
        transcript.gotoBottom();

        String rendered = view.render(state, transcript, editor(120), true, false);

        assertThat(rendered.lines()).hasSizeLessThanOrEqualTo(40);
        assertThat(rendered).contains("STUB-LONG-LINE-40", "Footer  Enter sends");
    }

    private Viewport transcript(TerminalUiState state) {
        Viewport transcript = Viewport.create(state.columns(), 2);
        transcript.setContent(view.transcriptContent(state));
        return transcript;
    }

    private Textarea editor(int columns) {
        Textarea editor = new Textarea();
        editor.setWidth(columns);
        editor.setHeight(3);
        editor.setMaxHeight(3);
        editor.setShowLineNumbers(false);
        editor.setPrompt("┃ ");
        editor.setPlaceholder("Type a message, /command, @file, !command, or !!command");
        editor.focus();
        return editor;
    }
}
