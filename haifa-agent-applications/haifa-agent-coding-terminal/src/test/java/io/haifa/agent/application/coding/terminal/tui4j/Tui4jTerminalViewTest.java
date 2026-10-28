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
import io.haifa.agent.application.coding.terminal.state.TerminalSelectorDetail;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.coding.terminal.state.ToolDetails;
import io.haifa.agent.application.coding.terminal.state.TranscriptItem;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.AgentSessionStatus;
import io.haifa.agent.project.domain.ProjectId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Tui4jTerminalViewTest {
    private final Tui4jTerminalView view = new Tui4jTerminalView();

    @Test
    void revealsTechnicalFactsSeparatelyFromTheToolResult() {
        var tool = item("tool-1", TranscriptItem.Kind.TOOL, "Read File · 阅读说明", "legacy body", "SUCCEEDED", true)
                .withToolDetails(new ToolDetails(
                        "Target: README.md\nOutput:\nReason: this is file content",
                        "Tool: file_read\nResult: internal-ref",
                        false));
        String primary = view.transcriptContent(withTranscript(defaultState(), List.of(tool)));
        String technical =
                view.transcriptContent(withTranscript(defaultState(), List.of(tool.toggleTechnicalDetails())));

        assertThat(primary)
                .contains("阅读说明", "Reason: this is file content", "Technical details", "ctrl+x t")
                .doesNotContain("internal-ref", "legacy body", "file_read");
        assertThat(technical).contains("Result: internal-ref");
    }

    @Test
    void finalResponseCopiesRenderedBodyAndLinksOnlyItsOwnRunActivity() {
        var oldTool = item("old-tool", TranscriptItem.Kind.TOOL, "Read File", "old output", "SUCCEEDED", false)
                .withSourceRunId("old-run");
        var tool = item("current-tool", TranscriptItem.Kind.TOOL, "Run Command", "output", "SUCCEEDED", false)
                .withSourceRunId("current-run");
        var response = item("response", TranscriptItem.Kind.ASSISTANT, "Assistant", "**完成**与 `code`", "FINAL", true)
                .withSourceRunId("current-run");
        var state = withTranscript(defaultState(), List.of(oldTool, tool, response));
        String rendered = view.transcriptContent(state);

        assertThat(rendered).contains("Assistant / Final response", "ctrl+x y copy", "ctrl+x a tool activity");
        assertThat(view.copyableFinalResponse(state))
                .hasValueSatisfying(
                        text -> assertThat(text).contains("完成", "code").doesNotContain("Assistant", "\u001B", "**"));
        assertThat(view.finalResponseActivityRow(state))
                .hasValueSatisfying(
                        row -> assertThat(view.toggleableItemAt(row)).contains("current-tool"));
        assertThat(response.body()).isEqualTo("**完成**与 `code`");
    }

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
    void rendersConnectionCenterCategoriesBadgesAndDescriptions() {
        TerminalUiState initial = TerminalUiState.initial(88, 30);
        TerminalUiState state = withSelector(
                initial,
                new TerminalSelector(
                        "auth-provider",
                        "Connect a model",
                        List.of("ChatGPT", "Antigravity", "DeepSeek"),
                        0,
                        List.of(
                                new TerminalSelectorDetail(
                                        "Subscription · No API key required", "Recommended", "Subscription"),
                                new TerminalSelectorDetail("Subscription · No API key required", "", "Subscription"),
                                new TerminalSelectorDetail("API Key · Pay as you go", "Current", "API Key"))));

        String rendered = view.render(state, transcript(state), editor(88), true, false);

        assertThat(rendered)
                .contains(
                        "Subscription", "API Key", "ChatGPT", "[Recommended]", "Antigravity", "DeepSeek", "[Current]");
    }

    @Test
    void keepsConnectionCenterUsableAtMinimumSize() {
        TerminalUiState initial = TerminalUiState.initial(60, 16);
        TerminalUiState state = withSelector(
                initial,
                new TerminalSelector(
                        "auth-provider",
                        "Connect a model",
                        List.of("ChatGPT", "DeepSeek"),
                        0,
                        List.of(
                                new TerminalSelectorDetail(
                                        "Subscription · No API key required", "Recommended", "Subscription"),
                                new TerminalSelectorDetail("API Key · Pay as you go", "", "API Key"))));

        String rendered = view.render(state, transcript(state), editor(60), true, false);

        assertThat(rendered).contains("Connect a model", "ChatGPT", "enter select");
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
        assertThat(firstTime).contains("Enter submit · Esc cancel").doesNotContain("enter send", "Enter update");

        String update = view.render(state, transcript, editor(80), true, false, Duration.ZERO, 0, true, true);
        assertThat(update).contains("Enter update · Esc cancel").doesNotContain("enter send", "Enter submit");
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
                        "The session changed while submitting;",
                        "retry",
                        "message.",
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
                        "✓ [workspace.read]",
                        "● [mvn test]",
                        "Approval · SHELL [pending]",
                        "Resource · Test report [exported]",
                        "Error · Tool failed [failed]")
                .contains("›")
                .doesNotContain("ctrl+o expand")
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
        List<String> lines = content.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .toList();

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("[file_stat]", "›").doesNotContain("Target:");
        assertThat(lines.get(1)).contains("[file_read]", "README.md", "›").doesNotContain("Target:");
        assertThat(content).doesNotContain("ctrl+o expand");
    }

    @Test
    void mapsClickRowsToTheCorrectTranscriptItems() {
        TerminalUiState state = withTranscript(
                defaultState(),
                List.of(
                        item("tool-1", TranscriptItem.Kind.TOOL, "file_read · a", "Output:\na", "SUCCEEDED", false),
                        item("tool-2", TranscriptItem.Kind.TOOL, "file_read · b", "Output:\nb", "SUCCEEDED", false),
                        item("tool-3", TranscriptItem.Kind.TOOL, "file_read · c", "Output:\nc", "SUCCEEDED", false)));

        view.transcriptContent(state);

        assertThat(view.toggleableItemAt(0)).contains("tool-1");
        assertThat(view.toggleableItemAt(1)).contains("tool-2");
        assertThat(view.toggleableItemAt(2)).contains("tool-3");
    }

    @Test
    void keepsNonZeroExitCodeWhenTheTargetCannotFit() {
        String target = "x".repeat(120);
        TerminalUiState state = withTranscript(
                TerminalUiState.initial(60, 30),
                List.of(new TranscriptItem(
                        "exec-1",
                        TranscriptItem.Kind.EXECUTION,
                        "execution_run · " + target,
                        "Command exited (exit 1)",
                        "EXITED",
                        false,
                        Optional.empty(),
                        Optional.of(1_000L),
                        Optional.of(850L),
                        Optional.of(1))));

        String content = view.transcriptContent(state);

        assertThat(content).contains("exit 1", "850 ms", "›").doesNotContain(target);
    }

    @Test
    void keepsCollapsedToolAndExecutionCardsToOneLineWithoutBodyPreview() {
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

        assertThat(toolContent.lines().filter(line -> !line.isBlank()).count()).isEqualTo(1);
        assertThat(toolContent)
                .contains("[file_read]", "README.md", "300 ms", "›")
                .doesNotContain("BUILD SUCCESS", "Target:", "Result:", "Duration", "ctrl+o expand");
        assertThat(executionContent.lines().filter(line -> !line.isBlank()).count())
                .isEqualTo(1);
        assertThat(executionContent)
                .contains("[execution_run]", "./mvnw test", "›")
                .doesNotContain(
                        "M src/App.java",
                        "Target:",
                        "Output (streaming):",
                        "[execution output truncated]",
                        "[preview output dropped]",
                        "Result:",
                        "ctrl+o expand");
    }

    @Test
    void rendersToolCardsWithLocalizedStatusGlyphAndMutedBadge() {
        Renderer renderer = Renderer.defaultRenderer();
        renderer.setColorProfile(ColorProfile.TrueColor);
        renderer.setHasDarkBackground(true);
        try {
            TerminalUiState state = withTranscript(
                    defaultState(),
                    List.of(
                            item(
                                    "tool-1",
                                    TranscriptItem.Kind.TOOL,
                                    "file_stat · local-tmp/test.log",
                                    "Inspected local-tmp/test.log",
                                    "SUCCEEDED",
                                    false),
                            item(
                                    "exec-1",
                                    TranscriptItem.Kind.EXECUTION,
                                    "execution_run · ./mvnw test",
                                    "Command exited (exit 0)",
                                    "SUCCEEDED",
                                    false)));

            String content = view.transcriptContent(state);

            String stripped = com.williamcallahan.tui4j.compat.x.ansi.Strip.strip(content);
            assertThat(stripped)
                    .contains("file_stat", "local-tmp/test.log")
                    .contains("execution_run", "./mvnw test")
                    .doesNotContain("Inspected local-tmp/test.log", "Command exited (exit 0)", "ctrl+o expand");

            // The status glyph and badge still use foreground/background colors.
            assertThat(content).contains("\u001B[3");
            assertThat(content).contains("\u001B[4");
        } finally {
            renderer.setColorProfile(ColorProfile.Ascii);
            renderer.setHasDarkBackground(false);
        }
    }

    @Test
    void omitsCollapsedBodyContentWhenEveryLineIsStructural() {
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

        assertThat(content.lines().filter(line -> !line.isBlank()).count()).isEqualTo(1);
        assertThat(content.strip())
                .contains("[file_write]", "src/x.java", "›")
                .doesNotContain("Target:", "Result:", "ctrl+o expand");
    }

    @Test
    void truncatesTheCollapsedToolTitleToTheTranscriptWidth() {
        String longLine = "x".repeat(200);
        TerminalUiState state = withTranscript(
                defaultState(),
                List.of(new TranscriptItem(
                        "tool-1",
                        TranscriptItem.Kind.TOOL,
                        "file_read · " + longLine,
                        "Output:\ncontent",
                        "SUCCEEDED",
                        false)));

        String content = view.transcriptContent(state);

        assertThat(content).contains("…", "[file_read]", "›").doesNotContain(longLine);
    }

    @Test
    void showsCollapsedDurationsAtEveryDurationAndKeepsThemWhenExpanded() {
        String atThreshold =
                view.transcriptContent(withTranscript(defaultState(), List.of(toolWithDuration(false, 10_000L))));
        String aboveThreshold =
                view.transcriptContent(withTranscript(defaultState(), List.of(toolWithDuration(false, 10_001L))));
        String shortExpanded =
                view.transcriptContent(withTranscript(defaultState(), List.of(toolWithDuration(true, 9_000L))));

        assertThat(atThreshold).contains("[file_read]", "README.md", "10s", "›");
        assertThat(aboveThreshold).contains("[file_read]", "README.md", "10s", "›");
        assertThat(shortExpanded).contains("[file_read]", "README.md", "9s", "⌄");
    }

    @Test
    void showsNonZeroExitCodeAsMutedFactWithoutMarkingExecutionAsFailure() {
        TerminalUiState nonZero = withTranscript(
                defaultState(),
                List.of(new TranscriptItem(
                        "exec-1",
                        TranscriptItem.Kind.EXECUTION,
                        "execution_run · python -m unittest",
                        "Command exited (exit 1)\nFAILED",
                        "EXITED",
                        false,
                        Optional.empty(),
                        Optional.of(1_000L),
                        Optional.of(850L),
                        Optional.of(1))));
        TerminalUiState zero = withTranscript(
                defaultState(),
                List.of(new TranscriptItem(
                        "exec-2",
                        TranscriptItem.Kind.EXECUTION,
                        "execution_run · git status",
                        "Command exited (exit 0)\nclean",
                        "EXITED",
                        false,
                        Optional.empty(),
                        Optional.of(1_000L),
                        Optional.of(20L),
                        Optional.of(0))));

        String nonZeroContent = view.transcriptContent(nonZero);
        String zeroContent = view.transcriptContent(zero);

        assertThat(nonZeroContent.lines().filter(line -> !line.isBlank()).count())
                .isEqualTo(1);
        assertThat(nonZeroContent)
                .contains("○", "[execution_run]", "exit 1", "850 ms", "›")
                .doesNotContain("✓", "✗", "Command exited (exit 1)", "ctrl+o expand");
        assertThat(zeroContent).contains("✓", "[execution_run]", "20 ms", "›").doesNotContain("exit 0");
    }

    @Test
    void collapsesOrdinaryToolErrorsWithoutShowingTheWholeResult() {
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
                .contains("! [workspace.read] missing.txt")
                .doesNotContain("Reason: NOT_FOUND", "Result: safe-ref", "Hidden detail", "ctrl+o expand");
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
                .contains("? [execution_run] rm -rf build", "Outcome: UNKNOWN", "Reason: AUTOMATIC_REPLAY_FORBIDDEN")
                .doesNotContain("✓", "✗", "ctrl+o expand");
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
                        "[file_read]", "README.md", "300 ms", "Duration 300 ms · 2 lines", "✓ Run completed · 4s", "›")
                .doesNotContain("Status: COMPLETED", "ctrl+o expand");
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
    void doesNotMiscolorOrdinaryToolOutputsAsDiff() {
        Renderer renderer = Renderer.defaultRenderer();
        renderer.setColorProfile(ColorProfile.TrueColor);
        renderer.setHasDarkBackground(true);
        try {
            String ordinaryBody =
                    """
                    Target: README.md
                    Output:
                    - item 1
                    - item 2
                    + note on addition
                    Result: ok""";

            TerminalUiState initial = TerminalUiState.initial(100, 30);
            TerminalUiState state = new TerminalUiState(
                    initial.header(),
                    initial.loadedResources(),
                    List.of(new TranscriptItem(
                            "tool-read-1",
                            TranscriptItem.Kind.TOOL,
                            "file_read · README.md",
                            ordinaryBody,
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

            // Lines with bullet lists (- item) should NOT be diff-colored in red or green
            assertThat(content).contains("  - item 1");
            assertThat(content).contains("  - item 2");
            assertThat(content).contains("  + note on addition");
            Tui4jTerminalTheme theme = new Tui4jTerminalTheme();
            assertThat(content).doesNotContain(theme.diffRemoved("- item 1"));
            assertThat(content).doesNotContain(theme.diffAdded("+ note on addition"));
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
                        "Technical details")
                .doesNotContain("digest-123");

        assertThat(view.transcriptContent(expanded)).contains("Technical details", "调用摘要: digest-123");
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
        assertThat(collapsed).contains("line-1", "line-5", "8 lines").doesNotContain("line-6");

        String expanded = view.transcriptContent(withApproval(initial, details, true));
        assertThat(expanded).contains("line-6", "line-8");
    }

    private TerminalUiState withSelector(TerminalUiState initial, TerminalSelector selector) {
        return new TerminalUiState(
                initial.header(),
                initial.loadedResources(),
                initial.transcript(),
                initial.pending(),
                initial.status(),
                initial.editorBuffer(),
                initial.editorCursor(),
                Optional.of(selector),
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
    void continuesTheSidebarDividerThroughTheComposerAndSelector() {
        for (int rows : List.of(18, 30)) {
            var initial = TerminalUiState.initial(120, rows);
            var selecting = new TerminalUiReducer()
                    .reduce(
                            initial,
                            new TerminalUiAction.SelectorOpened(
                                    new TerminalSelector("commands", "Commands", List.of("First", "Second"), 0)));
            for (var state : List.of(initial, selecting)) {
                String rendered = Strip.strip(view.render(state, transcript(state), editor(77), true, false));
                List<String> lines = rendered.lines().toList();
                assertThat(lines).hasSize(rows);
                assertThat(lines.subList(0, rows - 1)).allSatisfy(line -> {
                    int divider = line.lastIndexOf('│');
                    assertThat(divider).isGreaterThanOrEqualTo(0);
                    assertThat(TextWidth.measureCellWidth(line.substring(0, divider)))
                            .isEqualTo(77);
                    assertThat(TextWidth.measureCellWidth(line)).isLessThanOrEqualTo(119);
                });
                assertThat(view.transcriptRegion().height()).isLessThan(rows - 3);
            }
        }
    }

    @Test
    void showsSidebarOnWideTerminalWhenVisible() {
        TerminalUiState state = TerminalUiState.initial(120, 30);
        assertThat(state.sidebarVisible()).isTrue();

        String rendered = view.render(state, transcript(state), editor(120), true, false);

        assertThat(rendered).contains("Resources", "Haifa Agent v0.1.4-SNAPSHOT", "ctrl+x b toggle");
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
        assertThat(lines.get(0)).isEqualTo("│ ✖ Error · Process timeout [failed]");
        assertThat(lines.get(1)).isEqualTo("│ Subprocess timed out after 30000ms");
        assertThat(lines.get(2)).isEqualTo("│ Exit code: -1");

        for (String line : lines) {
            assertThat(TextWidth.measureCellWidth(line)).isLessThanOrEqualTo(80);
        }

        assertThat(content).doesNotContain("┌", "└", "─", "━");
    }

    @Test
    void rendersFailedToolCardWithLocalizedErrorTitleAndCleanBody() {
        TerminalUiState initial = TerminalUiState.initial(100, 30);
        TerminalUiState state = new TerminalUiState(
                initial.header(),
                initial.loadedResources(),
                List.of(item(
                        "tool-1",
                        TranscriptItem.Kind.TOOL,
                        "file_patch · src/App.java",
                        "--- a/src/App.java\n+++ b/src/App.java\n@@ -1,2 +1,2 @@\n-old\n+new",
                        "FAILED",
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

        String content = view.transcriptContent(state);

        assertThat(content).contains("! [file_patch] src/App.java");
        assertThat(content).contains("-old", "+new");
        // No background sequence anywhere in tool error output
        assertThat(content).doesNotContain("\u001B[4").doesNotContain("\u001B[10");
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

    @Test
    void rendersWorktreeAndWorktreeBranchUnderProjectAndBranchInSidebar() {
        TerminalUiState state = new TerminalUiState(
                "Haifa Coding Agent",
                List.of(),
                List.of(),
                List.of(),
                "Idle",
                "",
                0,
                Optional.empty(),
                new TerminalFooter(
                        "D:/workspace/haifa-agent",
                        "dev",
                        "D:/worktrees/feat-worktree",
                        "feat-worktree",
                        "session",
                        "queue: 0",
                        "provider",
                        "model",
                        "IDLE",
                        ""),
                120,
                30,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                java.util.Set.of(),
                Optional.empty(),
                TerminalActivity.initial(),
                false,
                true,
                true);

        String rendered = view.render(state, transcript(state), editor(80), true, false);

        assertThat(rendered)
                .contains("📁 D:/workspace/haifa-agent")
                .contains("🌿 dev")
                .contains("🌳 D:/worktrees/feat-worktree")
                .contains("  🌿 feat-worktree");
    }

    @Test
    void rendersDynamicSidebarTitleFromActiveRunTaskSummaryAndUserTranscript() {
        CodingSessionSummary summary = new CodingSessionSummary(
                new AgentSessionId("s-1"),
                new ProjectId("p-1"),
                "Original Session Name",
                AgentSessionStatus.ACTIVE,
                Optional.empty(),
                Optional.empty(),
                0,
                Instant.EPOCH,
                0);
        CodingSessionView sessionWithTask = new CodingSessionView(
                summary,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "digest",
                "profile",
                new io.haifa.agent.application.project.product.coding.CodingModelSelection(
                        new io.haifa.agent.application.project.product.coding.CodingModelOption(
                                "profile", "profile", "cfg", "Configured", java.util.Set.of(), 1),
                        0,
                        true),
                Optional.of("Dynamic Active Task"));

        TerminalUiState runningState = new TerminalUiState(
                "Haifa Coding Agent",
                List.of(),
                List.of(),
                List.of(),
                "THINKING",
                "",
                0,
                Optional.empty(),
                TerminalFooter.empty(),
                120,
                30,
                Optional.of(sessionWithTask),
                Optional.of(new AgentRunId("r-1")),
                Optional.empty(),
                java.util.Set.of(),
                Optional.empty(),
                TerminalActivity.initial(),
                false,
                true,
                true);

        String runningRendered = view.render(runningState, transcript(runningState), editor(80), true, false);
        assertThat(runningRendered).contains("Dynamic Active Task");

        CodingSessionView settledSession = new CodingSessionView(
                summary,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "digest",
                "profile",
                sessionWithTask.model(),
                Optional.empty());

        TerminalUiState settledState = new TerminalUiState(
                "Haifa Coding Agent",
                List.of(),
                List.of(
                        item("u-1", TranscriptItem.Kind.USER, "You", "First user prompt", "SENT", true),
                        item("u-2", TranscriptItem.Kind.USER, "You", "Second updated user prompt", "SENT", true)),
                List.of(),
                "Idle",
                "",
                0,
                Optional.empty(),
                TerminalFooter.empty(),
                120,
                30,
                Optional.of(settledSession),
                Optional.empty(),
                Optional.empty(),
                java.util.Set.of(),
                Optional.empty(),
                TerminalActivity.initial(),
                false,
                true,
                true);

        String settledRendered = view.render(settledState, transcript(settledState), editor(80), true, false);
        assertThat(settledRendered).contains("Second updated user prompt");
    }

    @Test
    void rendersArmedInterruptHintWhenCancelArmed() {
        TerminalUiState armedState = new TerminalUiState(
                "Haifa Coding Agent",
                List.of(),
                List.of(),
                List.of(),
                "Press esc again to cancel",
                "",
                0,
                Optional.empty(),
                TerminalFooter.empty(),
                80,
                24,
                Optional.empty(),
                Optional.of(new AgentRunId("r-1")),
                Optional.empty(),
                java.util.Set.of(),
                Optional.empty(),
                TerminalActivity.initial(),
                false,
                false,
                false,
                "dark",
                true);

        String rendered = view.render(armedState, transcript(armedState), editor(80), true, false);
        assertThat(rendered).contains("again to interrupt");
    }

    @Test
    void rendersWaitingRegionAboveEditorWithSendNowEditCancelActions() {
        TerminalUiState initial = TerminalUiState.initial(100, 30);
        TerminalUiState state = new TerminalUiState(
                        initial.header(),
                        initial.loadedResources(),
                        List.of(item(
                                "u1",
                                TranscriptItem.Kind.USER,
                                "You",
                                "Fix failing tests",
                                "SENT",
                                true)),
                        List.of(),
                        "WORKING",
                        "",
                        0,
                        Optional.empty(),
                        initial.footer(),
                        initial.columns(),
                        initial.rows(),
                        initial.session(),
                        Optional.of(new AgentRunId("run-1")),
                        initial.appliedCursor(),
                        initial.seenEventIds(),
                        Optional.empty(),
                        new TerminalActivity(1, "Run Command"),
                        false,
                        initial.sidebarVisible(),
                        initial.sidebarExplicitlyToggled(),
                        initial.themeName(),
                        false,
                        Optional.of(new io.haifa.agent.application.coding.terminal.state.WaitingMessage(
                                "wait-1",
                                "先别改 assertion，只修 checkpoint recovery 路径。",
                                io.haifa.agent.application.coding.terminal.state.WaitingMessage.Phase.WAITING)))
                .withStatus("WORKING");

        String rendered = Strip.strip(view.render(
                state, transcript(state), editor(120), true, false, Duration.ofSeconds(12)));

        assertThat(rendered)
                .contains(
                        "Waiting",
                        "hold until run finishes",
                        "checkpoint recovery",
                        "Send Now enter",
                        "Edit e",
                        "Cancel esc",
                        "WORKING (12s)")
                .doesNotContain("enter steer");
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
