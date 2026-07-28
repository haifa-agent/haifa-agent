package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.williamcallahan.tui4j.ansi.TextWidth;
import com.williamcallahan.tui4j.compat.bubbles.textarea.Textarea;
import com.williamcallahan.tui4j.compat.bubbles.viewport.Viewport;
import com.williamcallahan.tui4j.compat.lipgloss.color.NoColor;
import com.williamcallahan.tui4j.term.TerminalInfo;
import io.haifa.agent.application.coding.terminal.state.PendingMessage;
import io.haifa.agent.application.coding.terminal.state.TerminalFooter;
import io.haifa.agent.application.coding.terminal.state.TerminalSelector;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.coding.terminal.state.TranscriptItem;
import io.haifa.agent.core.run.AgentRunId;
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
                        "Haifa Coding Agent", "Start a task or use /commands.", "Type a message", "enter send", "IDLE")
                .doesNotContain(
                        "Diagnostics",
                        "Pending messages  none",
                        "Widgets above",
                        "Widgets below",
                        "provider: frozen",
                        "model: frozen",
                        "sandbox: frozen profile");
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
                        "Haifa Coding Agent",
                        "Start a task or use /commands.",
                        "Commands",
                        "/reload",
                        "enter select",
                        "IDLE")
                .doesNotContain("Diagnostics", "Pending messages  none", "Widgets above", "Widgets below");
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
        assertThat(rendered)
                .contains("COMPLETED")
                .doesNotContain("git: via safe read model", "provider: frozen", "model: frozen", "sandbox: frozen");
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
        assertThat(rendered).contains("STUB-LONG-LINE-40", "enter send", "IDLE");
    }

    @Test
    void changesTheEditorHintWhenARunIsActive() {
        TerminalUiState initial = TerminalUiState.initial(80, 24);
        TerminalUiState active = new TerminalUiState(
                initial.header(),
                initial.loadedResources(),
                initial.transcript(),
                initial.pending(),
                "Working",
                initial.editorBuffer(),
                initial.editorCursor(),
                initial.selector(),
                initial.footer(),
                initial.columns(),
                initial.rows(),
                initial.session(),
                Optional.of(new AgentRunId("run-1")),
                initial.appliedCursor(),
                initial.seenEventIds(),
                initial.recoverableError(),
                initial.exitRequested());

        String rendered = view.render(active, transcript(active), editor(80), true, false);

        assertThat(rendered)
                .contains(
                        "enter steer", "alt/option+enter follow-up", "alt/option+up restore queued message", "Working")
                .doesNotContain("enter send");
    }

    @Test
    void onlyShowsResourcesPendingAndRecoveryRegionsWhenTheyContainRealInformation() {
        TerminalUiState initial = TerminalUiState.initial(100, 30);
        TerminalUiState state = new TerminalUiState(
                initial.header(),
                List.of("AGENTS.md", "14 tools", "2 skills"),
                initial.transcript(),
                List.of(
                        new PendingMessage("steer-1", PendingMessage.Kind.STEER, "Check the diff", 1),
                        new PendingMessage("follow-up-1", PendingMessage.Kind.FOLLOW_UP, "Run tests", 2)),
                "Recovery required",
                initial.editorBuffer(),
                initial.editorCursor(),
                initial.selector(),
                new TerminalFooter(
                        "project-1",
                        "git: unavailable",
                        "retry task",
                        "queue: 2",
                        "provider: frozen",
                        "model: frozen",
                        "RUNNING",
                        "sandbox: frozen profile"),
                initial.columns(),
                initial.rows(),
                initial.session(),
                Optional.of(new AgentRunId("run-1")),
                initial.appliedCursor(),
                initial.seenEventIds(),
                Optional.of("ACTIVE_RUN_MISMATCH"),
                initial.exitRequested());

        String rendered = view.render(state, transcript(state), editor(100), true, false);

        assertThat(rendered)
                .containsSubsequence(
                        "resources · AGENTS.md · 14 tools · 2 skills",
                        "Pending · 2",
                        "[steer] Check the diff",
                        "[follow_up] Run tests",
                        "Retryable · ACTIVE_RUN_MISMATCH",
                        "The session changed while submitting; retry the message.",
                        "Type a message",
                        "enter steer",
                        "RUNNING · project-1 · retry task · queue: 2")
                .containsOnlyOnce("Retryable")
                .doesNotContain("provider: frozen", "model: frozen", "sandbox: frozen profile");
    }

    @Test
    void givesTranscriptKindsAndStatusesDistinctNoColorTextSemantics() {
        TerminalUiState initial = TerminalUiState.initial(100, 30);
        TerminalUiState state = new TerminalUiState(
                initial.header(),
                List.of("AGENTS.md", "14 tools", "2 skills"),
                List.of(
                        item("user", TranscriptItem.Kind.USER, "You", "Fix the retry loop", "SENT", true),
                        item(
                                "assistant",
                                TranscriptItem.Kind.ASSISTANT,
                                "Assistant",
                                "I will inspect it.",
                                "STREAMING",
                                true),
                        item(
                                "tool",
                                TranscriptItem.Kind.TOOL,
                                "workspace.read",
                                "RetryPolicy.java",
                                "SUCCEEDED",
                                false),
                        item("execution", TranscriptItem.Kind.EXECUTION, "mvn test", "14 tests", "STARTED", false),
                        item(
                                "approval",
                                TranscriptItem.Kind.APPROVAL,
                                "Approval · SHELL",
                                "Run tests",
                                "PENDING",
                                true),
                        item("resource", TranscriptItem.Kind.RESOURCE, "Test report", "artifact:1", "EXPORTED", false),
                        item("error", TranscriptItem.Kind.ERROR, "Tool failed", "Retry available", "FAILED", true)),
                initial.pending(),
                initial.status(),
                initial.editorBuffer(),
                initial.editorCursor(),
                initial.selector(),
                initial.footer(),
                initial.columns(),
                initial.rows(),
                initial.session(),
                initial.currentRunId(),
                initial.appliedCursor(),
                initial.seenEventIds(),
                initial.recoverableError(),
                initial.exitRequested());

        String content = view.transcriptContent(state);

        assertThat(content)
                .containsSubsequence(
                        "You",
                        "Assistant",
                        "Tool · workspace.read [succeeded]",
                        "Execution · mvn test [started]",
                        "Approval · SHELL [pending]",
                        "Resource · Test report [exported]",
                        "Error · Tool failed [failed]")
                .contains("ctrl+o expand")
                .doesNotContain("\u001B");
    }

    private TranscriptItem item(
            String id, TranscriptItem.Kind kind, String title, String body, String status, boolean expanded) {
        return new TranscriptItem(id, kind, title, body, status, expanded);
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
