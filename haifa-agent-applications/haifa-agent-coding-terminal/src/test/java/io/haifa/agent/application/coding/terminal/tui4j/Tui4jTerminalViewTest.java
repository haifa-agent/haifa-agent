package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.williamcallahan.tui4j.ansi.TextWidth;
import com.williamcallahan.tui4j.compat.bubbles.textarea.Textarea;
import com.williamcallahan.tui4j.compat.bubbles.viewport.Viewport;
import com.williamcallahan.tui4j.compat.lipgloss.Renderer;
import com.williamcallahan.tui4j.compat.lipgloss.color.ColorProfile;
import com.williamcallahan.tui4j.compat.lipgloss.color.NoColor;
import com.williamcallahan.tui4j.compat.x.ansi.Strip;
import com.williamcallahan.tui4j.term.TerminalInfo;
import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.coding.terminal.state.ApprovalDetails;
import io.haifa.agent.application.coding.terminal.state.PendingMessage;
import io.haifa.agent.application.coding.terminal.state.TerminalActivity;
import io.haifa.agent.application.coding.terminal.state.TerminalFooter;
import io.haifa.agent.application.coding.terminal.state.TerminalSelector;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.coding.terminal.state.TranscriptItem;
import io.haifa.agent.core.run.AgentRunId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
                        "Start a task or use /commands.",
                        "Type a message",
                        "Normal",
                        "[f2] models",
                        "[ctrl+p] commands")
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
    void keepsTheEmptyEditorAtThreeRowsAndRendersOneMutedEditorHint() {
        TerminalUiState state = TerminalUiState.initial(80, 24);
        String rendered = view.render(state, transcript(state), editor(80), true, false);
        List<String> lines = rendered.lines().toList();
        int editorLine = IntStream.range(0, lines.size())
                .filter(index -> lines.get(index).contains("Type a message"))
                .findFirst()
                .orElseThrow();
        int metaLine = IntStream.range(0, lines.size())
                .filter(index -> lines.get(index).contains("Normal"))
                .findFirst()
                .orElseThrow();

        assertThat(metaLine - editorLine).isEqualTo(3);
        assertThat(rendered).contains("[f2] models", "[ctrl+p] commands");
    }

    @Test
    void rendersMacSpecialShortcutNamesFromTheSamePlatformProfileUsedForInput() {
        TerminalHostInfo mac = TerminalHostInfo.detect(
                Map.of(
                        "os.name", "Mac OS X",
                        "os.version", "15.6",
                        "os.arch", "aarch64",
                        "java.version", "21"),
                List.of());
        Tui4jTerminalView macView = new Tui4jTerminalView(TerminalShortcutProfile.forHost(mac));
        TerminalUiState state = TerminalUiState.initial(100, 30);

        String rendered = macView.render(state, transcript(state), editor(100), true, false);

        assertThat(rendered)
                .contains("⌃X B toggle", "[F2] models", "[⌃P] commands")
                .doesNotContain("ctrl+p", "ctrl+x b");
    }

    @Test
    void keepsTerminalCapabilityCodesAndEscapeSequencesOutOfTheUserFacingNotice() {
        TerminalUiState state = new TerminalUiReducer()
                .reduce(
                        TerminalUiState.initial(100, 30),
                        new TerminalUiAction.RecoverableFailure("WINDOWS_TERMINAL_MODIFIED_ENTER_REMAP"));

        String rendered = view.render(state, transcript(state), editor(100), true, false);

        assertThat(rendered)
                .contains("Terminal capability", "Windows Terminal", "Ctrl+J", "custom key bindings")
                .doesNotContain("WINDOWS_TERMINAL_MODIFIED_ENTER_REMAP", "ESC[", "13;2u", "13;3u");
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
                .containsSubsequence("Start a task or use /commands.", "Commands", "/reload", "enter select")
                .doesNotContain("Diagnostics", "Pending messages  none", "Widgets above", "Widgets below");
        assertThat(rendered).contains("5-5 of 6", "editor preserved", "[f2] models", "[ctrl+p] commands");
        assertThat(rendered.lines()).hasSizeLessThanOrEqualTo(16);
    }

    @Test
    void keepsAVisibleSelectionMarkerWhenTerminalColorIsUnavailable() {
        TerminalUiState initial = TerminalUiState.initial(60, 24);
        TerminalUiState state = new TerminalUiState(
                initial.header(),
                initial.loadedResources(),
                initial.transcript(),
                initial.pending(),
                initial.status(),
                initial.editorBuffer(),
                initial.editorCursor(),
                Optional.of(new TerminalSelector(
                        "interaction:approval-1", "Approval · Approval required", List.of("approve", "reject"), 0)),
                initial.footer(),
                initial.columns(),
                initial.rows(),
                initial.session(),
                initial.currentRunId(),
                initial.appliedCursor(),
                initial.seenEventIds(),
                initial.recoverableError(),
                initial.exitRequested());

        String rendered = view.render(state, transcript(state), editor(60), true, false);

        assertThat(rendered).contains("│ > approve", "│   reject");
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
                        "feat-long-footer",
                        "a long session name that must remain on one physical row",
                        "queue: 0",
                        "provider: frozen",
                        "frozen-model",
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
        assertThat(rendered).contains("frozen-model", "feat-long-footer").doesNotContain("sandbox: frozen profile");
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
        assertThat(rendered).contains("STUB-LONG-LINE-40", "Type a message", "[f2] models");
    }

    @Test
    void changesStatusWhenARunIsActive() {
        TerminalUiState initial = TerminalUiState.initial(80, 24);
        TerminalUiState active = new TerminalUiState(
                initial.header(),
                initial.loadedResources(),
                initial.transcript(),
                initial.pending(),
                "THINKING",
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

        String rendered = view.render(active, transcript(active), editor(80), true, false, Duration.ofSeconds(125));

        assertThat(rendered).contains("THINKING (2m 5s)", "interrupt").doesNotContain("enter send");
    }

    @Test
    void adaptsEditorHintForSecureInputFirstTimeAndKeyUpdate() {
        TerminalUiState state = TerminalUiState.initial(80, 24);
        Viewport transcript = transcript(state);

        String firstTime = view.render(state, transcript, editor(80), true, false, Duration.ZERO, 0, true, false);
        assertThat(firstTime).contains("enter submit · escape cancel").doesNotContain("enter send", "enter update");

        String update = view.render(state, transcript, editor(80), true, false, Duration.ZERO, 0, true, true);
        assertThat(update).contains("enter update · escape cancel").doesNotContain("enter send", "enter submit");
    }

    @Test
    void timesThinkingFromOneSecondAndSwitchesToMinutesAfterSixtySeconds() {
        TerminalUiState initial = TerminalUiState.initial(80, 24);
        TerminalUiState running = new TerminalUiState(
                initial.header(),
                initial.loadedResources(),
                initial.transcript(),
                initial.pending(),
                "THINKING",
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

        assertThat(view.render(running, transcript(running), editor(80), true, false, Duration.ZERO))
                .contains("THINKING (1s)");
        assertThat(view.render(running, transcript(running), editor(80), true, false, Duration.ofSeconds(61)))
                .contains("THINKING (1m 1s)");
    }

    @Test
    void showsTheShortToolLabelBesideTheWorkingActivityTimer() {
        TerminalUiState initial = TerminalUiState.initial(80, 24);
        TerminalUiState working = new TerminalUiState(
                initial.header(),
                initial.loadedResources(),
                initial.transcript(),
                initial.pending(),
                "WORKING",
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
                new TerminalActivity(2, "execution_run"),
                initial.exitRequested());

        assertThat(view.render(working, transcript(working), editor(80), true, false, Duration.ofSeconds(12)))
                .contains("WORKING (12s) · execution_run");
    }

    @Test
    void showsSelectedModelWorkingDirectoryAndGitBranchBelowEnterSend() {
        TerminalUiState initial = TerminalUiState.initial(120, 30);
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
                        "D:\\workspace\\haifa-agent",
                        "feat-coding-terminal-mouse-scroll",
                        "terminal context",
                        "queue: 0",
                        "DeepSeek",
                        "DeepSeek V4 Flash",
                        "IDLE",
                        ""),
                initial.columns(),
                initial.rows(),
                initial.session(),
                initial.currentRunId(),
                initial.appliedCursor(),
                initial.seenEventIds(),
                initial.recoverableError(),
                initial.exitRequested());

        String rendered = view.render(state, transcript(state), editor(120), true, false);

        assertThat(rendered)
                .contains("📁 D:\\workspace\\haifa-agent", "🌿 feat-coding-terminal-mouse-scroll", "DeepSeek V4 Flash");
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
                        "",
                        "retry task",
                        "queue: 2",
                        "provider: frozen",
                        "frozen-model",
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
                .contains(
                        "• AGENTS.md",
                        "• 14 tools",
                        "• 2 skills",
                        "Pending · 2",
                        "[steer] Check the diff",
                        "[follow_up] Run tests",
                        "Retryable · ACTIVE_RUN_MISMATCH",
                        "The session changed while submitting; retry the message.",
                        "Type a message",
                        "frozen-model",
                        "queue: 2")
                .containsOnlyOnce("Retryable")
                .doesNotContain("sandbox: frozen profile");
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
                        "✓ workspace.read",
                        "● mvn test",
                        "Approval · SHELL [pending]",
                        "Resource · Test report [exported]",
                        "Error · Tool failed [failed]")
                .contains("ctrl+o expand")
                .doesNotContain("\u001B");
    }

    @Test
    void keepsConsecutiveSuccessfulToolsToOneLineEachWithoutBlankRows() {
        TerminalUiState initial = TerminalUiState.initial(100, 30);
        TerminalUiState state = new TerminalUiState(
                initial.header(),
                initial.loadedResources(),
                List.of(
                        item("tool-1", TranscriptItem.Kind.TOOL, "file_stat", "Target: file_stat", "SUCCEEDED", false),
                        item(
                                "tool-2",
                                TranscriptItem.Kind.TOOL,
                                "file_read · README.md",
                                "Target: README.md",
                                "SUCCEEDED",
                                false)),
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
        String withoutBlockPadding =
                content.lines().map(String::strip).collect(java.util.stream.Collectors.joining("\n"));

        assertThat(withoutBlockPadding)
                .isEqualTo(
                        """
                        ✓ file_stat · ctrl+o expand
                        ✓ file_read · README.md · ctrl+o expand""")
                .doesNotContain("Target:");
    }

    @Test
    void showsOneCollapsedBodyPreviewLineForToolAndExecutionCards() {
        TerminalUiState toolState = withTranscript(
                defaultState(),
                List.of(new TranscriptItem(
                        "tool-1",
                        TranscriptItem.Kind.TOOL,
                        "file_read · README.md",
                        "Target: README.md\nOutput:\nBUILD SUCCESS\nResult: artifact:1",
                        "SUCCEEDED",
                        false,
                        Optional.empty(),
                        Optional.of(1_000L),
                        Optional.of(300L))));
        TerminalUiState executionState = withTranscript(
                defaultState(),
                List.of(new TranscriptItem(
                        "execution-1",
                        TranscriptItem.Kind.EXECUTION,
                        "execution_run · ./mvnw test",
                        "Target: ./mvnw test\nOutput (streaming):\n[execution output truncated]\n"
                                + "[preview output dropped]\nM src/App.java\nResult: artifact:9",
                        "SUCCEEDED",
                        false,
                        Optional.empty(),
                        Optional.of(1_000L),
                        Optional.empty())));

        String toolContent = view.transcriptContent(toolState);
        String executionContent = view.transcriptContent(executionState);

        assertThat(toolContent)
                .contains("✓ file_read · README.md · ctrl+o expand", "BUILD SUCCESS")
                .doesNotContain("Target:", "Result:", "Duration", "· 300 ms");
        assertThat(executionContent)
                .contains("M src/App.java")
                .doesNotContain(
                        "Target:",
                        "Output (streaming):",
                        "[execution output truncated]",
                        "[preview output dropped]",
                        "Result:");
    }

    @Test
    void omitsThePreviewWhenEveryBodyLineIsStructural() {
        TerminalUiState state = withTranscript(
                defaultState(),
                List.of(new TranscriptItem(
                        "tool-1",
                        TranscriptItem.Kind.TOOL,
                        "file_write · src/x.java",
                        "Target: src/x.java\nResult: artifact:1",
                        "SUCCEEDED",
                        false)));

        String content = view.transcriptContent(state);

        assertThat(content.strip()).isEqualTo("✓ file_write · src/x.java · ctrl+o expand");
    }

    @Test
    void truncatesTheCollapsedPreviewToTheTranscriptWidth() {
        String longLine = "x".repeat(200);
        TerminalUiState state = withTranscript(
                defaultState(),
                List.of(new TranscriptItem(
                        "tool-1",
                        TranscriptItem.Kind.TOOL,
                        "file_read · big.txt",
                        "Output:\n" + longLine,
                        "SUCCEEDED",
                        false)));

        String content = view.transcriptContent(state);

        assertThat(content).contains("…").doesNotContain(longLine);
    }

    @Test
    void hidesCollapsedDurationsAtOrBelowTenSecondsAndAlwaysShowsThemWhenExpanded() {
        String atThreshold =
                view.transcriptContent(withTranscript(defaultState(), List.of(toolWithDuration(false, 10_000L))));
        String aboveThreshold =
                view.transcriptContent(withTranscript(defaultState(), List.of(toolWithDuration(false, 10_001L))));
        String shortExpanded =
                view.transcriptContent(withTranscript(defaultState(), List.of(toolWithDuration(true, 9_000L))));

        assertThat(atThreshold.strip()).isEqualTo("✓ file_read · README.md · ctrl+o expand");
        assertThat(aboveThreshold).contains("✓ file_read · README.md · 10s · ctrl+o expand");
        assertThat(shortExpanded).contains("✓ file_read · README.md · 9s");
    }

    @Test
    void keepsCollapsedToolErrorsActionableWithoutShowingTheWholeResult() {
        TerminalUiState initial = TerminalUiState.initial(100, 30);
        TerminalUiState state = new TerminalUiState(
                initial.header(),
                initial.loadedResources(),
                List.of(item(
                        "tool-1",
                        TranscriptItem.Kind.TOOL,
                        "workspace.read · missing.txt",
                        "Reason: NOT_FOUND\nResult: safe-ref\nHidden detail",
                        "FAILED",
                        false)),
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
                .contains("✗ workspace.read · missing.txt · ctrl+o expand", "Reason: NOT_FOUND", "Result: safe-ref")
                .doesNotContain("Hidden detail");
    }

    @Test
    void rendersUnknownToolOutcomeDistinctlyFromSuccessAndFailure() {
        TerminalUiState initial = TerminalUiState.initial(100, 30);
        TerminalUiState state = new TerminalUiState(
                initial.header(),
                initial.loadedResources(),
                List.of(item(
                        "tool-1",
                        TranscriptItem.Kind.TOOL,
                        "execution_run · rm -rf build",
                        "Outcome: UNKNOWN\nReason: AUTOMATIC_REPLAY_FORBIDDEN\nResult: asset-1",
                        "OUTCOME_UNKNOWN",
                        false)),
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
                .contains(
                        "? execution_run · rm -rf build · ctrl+o expand",
                        "Outcome: UNKNOWN",
                        "Reason: AUTOMATIC_REPLAY_FORBIDDEN")
                .doesNotContain("✓", "✗");
    }

    @Test
    void rendersDurationsRunSummaryChipsAndExpandedToolMetadata() {
        TerminalUiState initial = TerminalUiState.initial(100, 30);
        TerminalUiState state = new TerminalUiState(
                initial.header(),
                initial.loadedResources(),
                List.of(
                        new TranscriptItem(
                                "tool-1",
                                TranscriptItem.Kind.TOOL,
                                "file_read · README.md",
                                "Target: README.md\nResult: artifact:tool-1",
                                "SUCCEEDED",
                                true,
                                Optional.empty(),
                                Optional.of(1_000L),
                                Optional.of(300L)),
                        item(
                                "run-summary-run-1",
                                TranscriptItem.Kind.SUMMARY,
                                "Run completed · 4s",
                                "Status: COMPLETED\nDuration: 4s",
                                "COMPLETED",
                                false)),
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
                .contains(
                        "✓ file_read · README.md · 300 ms",
                        "Duration 300 ms · 2 lines",
                        "✓ Run completed · 4s · ctrl+o expand")
                .doesNotContain("Status: COMPLETED");
    }

    @Test
    void highlightsUnifiedDiffOutputInsideExpandedToolBody() {
        Renderer renderer = Renderer.defaultRenderer();
        renderer.setColorProfile(ColorProfile.TrueColor);
        renderer.setHasDarkBackground(true);
        try {

            String diffBody =
                    """
                Target: src/App.java
                Output:
                --- a/src/App.java
                +++ b/src/App.java
                @@ -1,3 +1,3 @@
                -int counter = 0;
                +int counter = 1;
                 return counter;
                Result: ok""";

            TerminalUiState initial = TerminalUiState.initial(100, 30);
            TerminalUiState state = new TerminalUiState(
                    initial.header(),
                    initial.loadedResources(),
                    List.of(new TranscriptItem(
                            "tool-patch-1",
                            TranscriptItem.Kind.TOOL,
                            "file_patch · src/App.java",
                            diffBody,
                            "SUCCEEDED",
                            true,
                            Optional.empty(),
                            Optional.of(1_000L),
                            Optional.of(150L))),
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

            assertThat(content).contains("\u001B[");
            String stripped = Strip.strip(content);
            assertThat(stripped)
                    .contains(
                            "--- a/src/App.java",
                            "+++ b/src/App.java",
                            "@@ -1,3 +1,3 @@",
                            "-int counter = 0;",
                            "+int counter = 1;",
                            " return counter;");
            assertThat(stripped).contains("Duration 150 ms");
        } finally {
            renderer.setColorProfile(ColorProfile.Ascii);
            renderer.setHasDarkBackground(false);
        }
    }

    @Test
    void rendersAssistantMarkdownWithoutPersistingTerminalStylesInState() {
        TerminalUiState initial = TerminalUiState.initial(100, 30);
        TranscriptItem assistant = item(
                "assistant-1",
                TranscriptItem.Kind.ASSISTANT,
                "Assistant",
                "# Result\nUse **tests** and `verify`.",
                "STREAMING",
                true);
        TerminalUiState state = new TerminalUiState(
                initial.header(),
                initial.loadedResources(),
                List.of(assistant),
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

        assertThat(view.transcriptContent(state))
                .contains("Assistant", "Result", "Use tests and verify.")
                .doesNotContain("# Result", "**", "`");
        assertThat(assistant.body()).isEqualTo("# Result\nUse **tests** and `verify`.");
    }

    @Test
    void clipsCjkAndEmojiByTerminalCellsAndStripsInjectedControls() {
        TerminalUiState initial = TerminalUiState.initial(60, 16);
        TerminalUiState state = new TerminalUiState(
                initial.header(),
                initial.loadedResources(),
                List.of(item(
                        "tool",
                        TranscriptItem.Kind.TOOL,
                        "workspace\u001B.read",
                        "中".repeat(40) + "\tSAFE\u001B[31m",
                        "SUCCEEDED",
                        true)),
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

        String rendered = view.render(state, transcript(state), editor(60), true, false);

        assertThat(rendered.lines()).allMatch(line -> TextWidth.measureCellWidth(line) <= 59);
        assertThat(rendered).doesNotContain("\u001B", "\t").contains("workspace.read", "SAFE");
    }

    @Test
    void rendersStructuredApprovalAndKeepsTechnicalDetailsCollapsedUntilExpanded() {
        ApprovalDetails details = new ApprovalDetails(
                "执行 PowerShell 命令",
                "为了观察终端工具调用的实际效果。",
                "PowerShell",
                "Start-Sleep -Seconds 4",
                List.of(new ApprovalDetails.Fact("执行位置", "本机环境")),
                List.of(new ApprovalDetails.Fact("调用摘要", "digest-123")),
                Optional.of("HIGH"),
                List.of("reject", "approve"));
        TerminalUiState initial = TerminalUiState.initial(120, 40);
        TerminalUiState collapsed = withApproval(initial, details, false);
        TerminalUiState expanded = withApproval(initial, details, true);

        String collapsedContent = view.transcriptContent(collapsed);
        assertThat(collapsedContent)
                .contains(
                        "Approval · 执行 PowerShell 命令 [pending]",
                        "为了观察终端工具调用的实际效果。",
                        "PowerShell",
                        "Start-Sleep -Seconds 4",
                        "执行位置：本机环境",
                        "技术细节（可选）")
                .doesNotContain("digest-123");

        assertThat(view.transcriptContent(expanded)).contains("技术细节", "调用摘要: digest-123");
    }

    @Test
    void collapsesLongApprovalContentUntilExpanded() {
        String content = IntStream.rangeClosed(1, 8)
                .mapToObj(index -> "line-" + index)
                .collect(java.util.stream.Collectors.joining("\n"));
        ApprovalDetails details = new ApprovalDetails(
                "执行 PowerShell 命令",
                "为了观察终端工具调用的实际效果。",
                "PowerShell",
                content,
                List.of(),
                List.of(),
                Optional.empty(),
                List.of("approve", "reject"));
        TerminalUiState initial = TerminalUiState.initial(120, 40);

        String collapsed = view.transcriptContent(withApproval(initial, details, false));
        assertThat(collapsed).contains("line-1", "line-5", "共 8 行").doesNotContain("line-6");

        String expanded = view.transcriptContent(withApproval(initial, details, true));
        assertThat(expanded).contains("line-6", "line-8");
    }

    private TerminalUiState withTranscript(TerminalUiState initial, List<TranscriptItem> items) {
        return new TerminalUiState(
                initial.header(),
                initial.loadedResources(),
                items,
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
    }

    private TerminalUiState withApproval(TerminalUiState initial, ApprovalDetails details, boolean expanded) {
        return withTranscript(
                initial,
                List.of(new TranscriptItem(
                        "interaction-1",
                        TranscriptItem.Kind.APPROVAL,
                        "Approval · " + details.title(),
                        details.content(),
                        "PENDING",
                        expanded,
                        Optional.of(details))));
    }

    private TerminalUiState defaultState() {
        return TerminalUiState.initial(100, 30);
    }

    private TranscriptItem toolWithDuration(boolean expanded, long durationMillis) {
        return new TranscriptItem(
                "tool-1",
                TranscriptItem.Kind.TOOL,
                "file_read · README.md",
                "Target: README.md",
                "SUCCEEDED",
                expanded,
                Optional.empty(),
                Optional.of(1_000L),
                Optional.of(durationMillis));
    }

    @Test
    void showsSidebarOnWideTerminalWhenVisible() {
        TerminalUiState state = TerminalUiState.initial(120, 30);
        assertThat(state.sidebarVisible()).isTrue();

        String rendered = view.render(state, transcript(state), editor(120), true, false);

        assertThat(rendered).contains("Resources", "Haifa Agent v0.1.2", "ctrl+x b toggle");
    }

    @Test
    void hidesSidebarWhenToggledOffOrOnNarrowTerminal() {
        TerminalUiState wideToggledOff =
                new TerminalUiReducer().reduce(TerminalUiState.initial(120, 30), new TerminalUiAction.SidebarToggled());

        String wideHidden = view.render(wideToggledOff, transcript(wideToggledOff), editor(120), true, false);
        assertThat(wideHidden).doesNotContain("ctrl+x b toggle");

        TerminalUiState narrow = TerminalUiState.initial(80, 24);
        String narrowRendered = view.render(narrow, transcript(narrow), editor(80), true, false);
        assertThat(narrowRendered).doesNotContain("ctrl+x b toggle");
    }

    @Test
    void bottomStatusLineShowsOperationFeedbackWhenIdle() {
        TerminalUiState state = new TerminalUiReducer()
                .reduce(
                        TerminalUiState.initial(80, 24),
                        new TerminalUiAction.StatusChanged("Type first message to start session"));

        String rendered = view.render(state, transcript(state), editor(80), true, false);
        assertThat(rendered).contains("Type first message to start session");
    }

    @Test
    void bottomStatusLineShowsRunningActivityAndNewOutputBelowAlert() {
        TerminalUiState base = TerminalUiState.initial(100, 30);
        TerminalUiState state = new TerminalUiState(
                base.header(),
                base.loadedResources(),
                base.transcript(),
                base.pending(),
                "Thinking",
                base.editorBuffer(),
                base.editorCursor(),
                base.selector(),
                base.footer(),
                100,
                30,
                base.session(),
                Optional.of(new AgentRunId("run-1")),
                base.appliedCursor(),
                base.seenEventIds(),
                base.recoverableError(),
                new TerminalActivity(1, "Thinking"),
                base.exitRequested(),
                base.sidebarVisible(),
                base.sidebarExplicitlyToggled());

        String rendered = view.render(state, transcript(state), editor(100), false, true, Duration.ofSeconds(3));
        assertThat(rendered).contains("THINKING (3s)").contains("esc interrupt").contains("new output below");
    }

    @Test
    void rendersUserMessageAsLeftBorderCardWithoutEnclosingBoxLines() {
        TerminalUiState base = TerminalUiState.initial(80, 24);
        TerminalUiState state = new TerminalUiState(
                base.header(),
                base.loadedResources(),
                List.of(item(
                        "u-1",
                        TranscriptItem.Kind.USER,
                        "You",
                        "Please fix the flaky test\nand check git status",
                        "SENT",
                        true)),
                base.pending(),
                base.status(),
                base.editorBuffer(),
                base.editorCursor(),
                base.selector(),
                base.footer(),
                80,
                24,
                base.session(),
                base.currentRunId(),
                base.appliedCursor(),
                base.seenEventIds(),
                base.recoverableError(),
                base.exitRequested());

        String content = view.transcriptContent(state);
        List<String> lines = content.lines().toList();

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).startsWith("│ ❯ You");
        assertThat(lines.get(1)).startsWith("│ Please fix the flaky test");
        assertThat(lines.get(2)).startsWith("│ and check git status");

        // Cell layout contract: every card line must have cell width equal to mainWidth (80)
        for (String line : lines) {
            assertThat(TextWidth.measureCellWidth(line)).isEqualTo(80);
        }

        // Viewport height preservation: no enclosing top/bottom borders or extra blank framing
        assertThat(content).doesNotContain("┌", "└", "─", "━");
    }

    @Test
    void rendersErrorMessageAsLeftBorderCardWithDualCuesAndPreservedDiagnostics() {
        TerminalUiState base = TerminalUiState.initial(80, 24);
        TerminalUiState state = new TerminalUiState(
                base.header(),
                base.loadedResources(),
                List.of(item(
                        "e-1",
                        TranscriptItem.Kind.ERROR,
                        "Process timeout",
                        "Subprocess timed out after 30000ms\nExit code: -1",
                        "FAILED",
                        true)),
                base.pending(),
                base.status(),
                base.editorBuffer(),
                base.editorCursor(),
                base.selector(),
                base.footer(),
                80,
                24,
                base.session(),
                base.currentRunId(),
                base.appliedCursor(),
                base.seenEventIds(),
                base.recoverableError(),
                base.exitRequested());

        String content = view.transcriptContent(state);
        List<String> lines = content.lines().toList();

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).startsWith("│ ✖ Error · Process timeout [failed]");
        assertThat(lines.get(1)).startsWith("│ Subprocess timed out after 30000ms");
        assertThat(lines.get(2)).startsWith("│ Exit code: -1");

        for (String line : lines) {
            assertThat(TextWidth.measureCellWidth(line)).isEqualTo(80);
        }

        assertThat(content).doesNotContain("┌", "└", "─", "━");
    }

    @Test
    void degradesLeftBorderToSingleSpaceIndentInNarrowMode() {
        TerminalUiState base = TerminalUiState.initial(36, 20);
        TerminalUiState state = new TerminalUiState(
                base.header(),
                base.loadedResources(),
                List.of(item("u-1", TranscriptItem.Kind.USER, "You", "Narrow terminal check", "SENT", true)),
                base.pending(),
                base.status(),
                base.editorBuffer(),
                base.editorCursor(),
                base.selector(),
                base.footer(),
                36,
                20,
                base.session(),
                base.currentRunId(),
                base.appliedCursor(),
                base.seenEventIds(),
                base.recoverableError(),
                base.exitRequested());

        String content = view.transcriptContent(state);
        List<String> lines = content.lines().toList();

        assertThat(lines).hasSize(2);
        // In narrow mode (< 40 cols), left vertical bar degrades to a single space
        assertThat(lines.get(0)).startsWith(" ❯ You").doesNotStartWith("│");
        assertThat(lines.get(1)).startsWith(" Narrow terminal check").doesNotStartWith("│");

        for (String line : lines) {
            assertThat(TextWidth.measureCellWidth(line)).isEqualTo(36);
        }
    }

    @Test
    void handlesMultilingualAndWideCharactersWithoutCellOverflow() {
        TerminalUiState base = TerminalUiState.initial(80, 24);
        TerminalUiState state = new TerminalUiState(
                base.header(),
                base.loadedResources(),
                List.of(item("u-1", TranscriptItem.Kind.USER, "You", "用户需求：重构终端卡片渲染 🚀 包含 CJK 宽字符", "SENT", true)),
                base.pending(),
                base.status(),
                base.editorBuffer(),
                base.editorCursor(),
                base.selector(),
                base.footer(),
                80,
                24,
                base.session(),
                base.currentRunId(),
                base.appliedCursor(),
                base.seenEventIds(),
                base.recoverableError(),
                base.exitRequested());

        String content = view.transcriptContent(state);
        List<String> lines = content.lines().toList();

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).startsWith("│ ❯ You");
        assertThat(lines.get(1)).contains("用户需求：重构终端卡片渲染");

        for (String line : lines) {
            assertThat(TextWidth.measureCellWidth(line)).isEqualTo(80);
        }
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
