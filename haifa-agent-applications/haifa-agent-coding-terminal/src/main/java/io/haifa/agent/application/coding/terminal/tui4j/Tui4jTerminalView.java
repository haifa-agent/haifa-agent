package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.ansi.TextWidth;
import com.williamcallahan.tui4j.ansi.TextWrapper;
import com.williamcallahan.tui4j.ansi.Truncate;
import com.williamcallahan.tui4j.compat.bubbles.textarea.Textarea;
import com.williamcallahan.tui4j.compat.bubbles.viewport.Viewport;
import io.haifa.agent.application.coding.terminal.markdown.render.MarkdownRenderer;
import io.haifa.agent.application.coding.terminal.state.ApprovalDetails;
import io.haifa.agent.application.coding.terminal.state.PendingMessage;
import io.haifa.agent.application.coding.terminal.state.TerminalDurations;
import io.haifa.agent.application.coding.terminal.state.TerminalRecovery;
import io.haifa.agent.application.coding.terminal.state.TerminalSelector;
import io.haifa.agent.application.coding.terminal.state.TerminalSelectorDetail;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.coding.terminal.state.ToolBodyLines;
import io.haifa.agent.application.coding.terminal.state.TranscriptItem;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Composes the reviewed Prototype's single-column regions using tui4j components. */
final class Tui4jTerminalView {
    private static final int MIN_COLUMNS = 60;
    private static final int MIN_ROWS = 16;
    /** Paste blocks the model puts in the editor: the receiving block and the settled {@code [Pasted …]} summary. */
    private static final Pattern PASTE_PLACEHOLDER =
            Pattern.compile("\\[Past(?:ed #\\d+ ~\\d+ lines|ing ~\\d+ lines)\\]");
    /** Selector kind prefix of a clarification wizard, whose rows carry answer markers instead of plain labels. */
    private static final String CLARIFICATION_SELECTOR_PREFIX = "clarification:";

    private static final Set<String> SUCCESS_STATUSES = Set.of(
            "ACCEPTED",
            "APPLIED",
            "APPROVED",
            "COMPLETED",
            "EXITED",
            "EXPORTED",
            "PASSED",
            "RESOURCES RELOADED FOR FUTURE NEW RUNS",
            "SESSION ARCHIVED",
            "SESSION CONTEXT COMPACTED",
            "SESSION EXPORTED",
            "SESSION RENAMED",
            "SUCCEEDED",
            "SUCCESS");
    private static final Set<String> PENDING_STATUSES = Set.of(
            "CANCELLING",
            "PENDING",
            "QUEUED",
            "REQUESTED",
            "RUNNING",
            "STARTED",
            "STREAMING",
            "THINKING",
            "WAITING",
            "WORKING");
    private static final Set<String> TIMED_RUN_STATUSES = Set.of(
            "APPLYING STEER",
            "CANCELLING",
            "COMPLETION DEFERRED",
            "PENDING",
            "QUEUED",
            "REQUESTED",
            "RUNNING",
            "STARTED",
            "STREAMING",
            "SUBMITTING",
            "THINKING",
            "WAITING",
            "WAITING FOR APPROVAL",
            "WORKING");
    private static final Set<String> ERROR_STATUSES = Set.of(
            "ATTENTION",
            "CANCELLED",
            "DENIED",
            "ERROR",
            "FAILED",
            "RECOVERY REQUIRED",
            "REJECTED",
            "SHELL COMMAND DENIED",
            "TIMEOUT");
    private static final Set<String> NEUTRAL_STATUSES =
            Set.of("CANCELLED", "DENIED", "REJECTED", "SHELL COMMAND DENIED");
    private static final Set<String> TOOL_ORDINARY_FAILURE_STATUSES = Set.of("FAILED", "ERROR", "TIMEOUT");
    private static final Set<String> TOOL_BLOCKING_STATUSES = Set.of("ATTENTION", "RECOVERY REQUIRED");
    private static final Set<String> UNKNOWN_STATUSES = Set.of("OUTCOME_UNKNOWN", "UNKNOWN_OUTCOME");

    private final Tui4jTerminalTheme theme = new Tui4jTerminalTheme();
    private final MarkdownRenderer markdown = MarkdownRendererFactory.create(theme);
    private final TextWrapper textWrapper = new TextWrapper();
    private final TerminalShortcutProfile shortcuts;
    private TranscriptRegion transcriptRegion = new TranscriptRegion(0, 0, 1, 1);
    private List<TranscriptHit> transcriptHits = List.of();

    Tui4jTerminalView() {
        this(TerminalShortcutProfile.standard());
    }

    Tui4jTerminalView(TerminalShortcutProfile shortcuts) {
        this.shortcuts = java.util.Objects.requireNonNull(shortcuts, "shortcuts must not be null");
    }

    void setTheme(String themeName) {
        theme.switchTheme(themeName);
        markdown.resetAll();
    }

    Tui4jTerminalTheme theme() {
        return theme;
    }

    static int editorColumns(TerminalUiState state) {
        return Math.max(1, state.columns() - (state.sidebarVisible() && state.columns() >= 100 ? 43 : 1));
    }

    TranscriptRegion transcriptRegion() {
        return transcriptRegion;
    }

    /** Two-dimensional transcript viewport bounds in screen cells; the sidebar column is excluded. */
    record TranscriptRegion(int topRow, int leftColumn, int height, int width) {
        boolean contains(int row, int column) {
            return row >= topRow && row < topRow + height && column >= leftColumn && column < leftColumn + width;
        }

        int bottomRow() {
            return topRow + height - 1;
        }

        int clampRow(int row) {
            return Math.max(topRow, Math.min(bottomRow(), row));
        }

        int clampColumn(int column) {
            return Math.max(leftColumn, Math.min(leftColumn + width - 1, column));
        }

        int relativeRow(int row) {
            return row - topRow;
        }
    }

    /** One toggleable transcript item's inclusive logical row range in the rendered transcript. */
    record TranscriptHit(int startRow, int endRow, String itemId) {
        boolean contains(int row) {
            return row >= startRow && row <= endRow;
        }
    }

    Optional<String> toggleableItemAt(int logicalRow) {
        return transcriptHits.stream()
                .filter(hit -> hit.contains(logicalRow))
                .map(TranscriptHit::itemId)
                .findFirst();
    }

    Optional<TranscriptItem> finalResponse(TerminalUiState state) {
        return state.transcript().stream()
                .filter(item -> item.kind() == TranscriptItem.Kind.ASSISTANT && "FINAL".equals(item.status()))
                .reduce((first, second) -> second);
    }

    Optional<String> copyableFinalResponse(TerminalUiState state) {
        int width = Math.max(
                12, (state.sidebarVisible() && state.columns() >= 100 ? state.columns() - 43 : state.columns()) - 2);
        return finalResponse(state)
                .map(item -> TerminalScreenCells.plainText(markdown.render(item.id(), item.body(), width)));
    }

    Optional<Integer> finalResponseActivityRow(TerminalUiState state) {
        return finalResponse(state)
                .flatMap(response -> response.sourceRunId().flatMap(runId -> state.transcript().stream()
                        .filter(item -> item.kind() == TranscriptItem.Kind.TOOL
                                && item.sourceRunId().filter(runId::equals).isPresent())
                        .findFirst()
                        .flatMap(item -> transcriptHits.stream()
                                .filter(hit -> hit.itemId().equals(item.id()))
                                .map(TranscriptHit::startRow)
                                .findFirst())));
    }

    String render(
            TerminalUiState state,
            Viewport transcript,
            Textarea editor,
            boolean followTranscript,
            boolean newOutputPending) {
        return render(state, transcript, editor, followTranscript, newOutputPending, Duration.ZERO, 0, false, false);
    }

    String render(
            TerminalUiState state,
            Viewport transcript,
            Textarea editor,
            boolean followTranscript,
            boolean newOutputPending,
            Duration activityElapsed) {
        return render(state, transcript, editor, followTranscript, newOutputPending, activityElapsed, 0, false, false);
    }

    String render(
            TerminalUiState state,
            Viewport transcript,
            Textarea editor,
            boolean followTranscript,
            boolean newOutputPending,
            Duration activityElapsed,
            int requestedScrollRows) {
        return render(
                state,
                transcript,
                editor,
                followTranscript,
                newOutputPending,
                activityElapsed,
                requestedScrollRows,
                false,
                false);
    }

    String render(
            TerminalUiState state,
            Viewport transcript,
            Textarea editor,
            boolean followTranscript,
            boolean newOutputPending,
            Duration activityElapsed,
            int requestedScrollRows,
            boolean secureInput,
            boolean isUpdate) {
        return render(
                state,
                transcript,
                editor,
                followTranscript,
                newOutputPending,
                activityElapsed,
                requestedScrollRows,
                secureInput,
                isUpdate,
                null);
    }

    String render(
            TerminalUiState state,
            Viewport transcript,
            Textarea editor,
            boolean followTranscript,
            boolean newOutputPending,
            Duration activityElapsed,
            int requestedScrollRows,
            boolean secureInput,
            boolean isUpdate,
            String secureInputHint) {
        return render(
                state,
                transcript,
                editor,
                followTranscript,
                newOutputPending,
                activityElapsed,
                requestedScrollRows,
                secureInput,
                isUpdate,
                secureInputHint,
                true);
    }

    String render(
            TerminalUiState state,
            Viewport transcript,
            Textarea editor,
            boolean followTranscript,
            boolean newOutputPending,
            Duration activityElapsed,
            int requestedScrollRows,
            boolean secureInput,
            boolean isUpdate,
            String secureInputHint,
            boolean mouseReporting) {
        if (state.columns() < MIN_COLUMNS || state.rows() < MIN_ROWS) {
            return String.join(
                    "\n",
                    "Haifa Coding Agent",
                    "Terminal is too small",
                    "Required: at least " + MIN_COLUMNS + "x" + MIN_ROWS,
                    "Current: " + state.columns() + "x" + state.rows(),
                    "Resize the terminal to continue.");
        }

        boolean compact = state.rows() < 24;
        boolean showSidebar = state.sidebarVisible() && state.columns() >= 100;
        int sidebarWidth = showSidebar ? 42 : 0;
        int mainWidth = state.columns() - sidebarWidth;

        List<String> after = lowerRegions(
                state,
                editor,
                newOutputPending && !followTranscript,
                compact,
                activityElapsed,
                secureInput,
                isUpdate,
                secureInputHint,
                mouseReporting,
                editorColumns(state));
        int viewportRows = Math.max(1, state.rows() - visualRows(after));
        int transcriptWidth = showSidebar ? Math.max(12, mainWidth - 1) : Math.max(12, mainWidth);
        transcript.setWidth(transcriptWidth);
        transcript.setHeight(viewportRows);
        transcriptRegion = new TranscriptRegion(0, 0, viewportRows, transcriptWidth);
        if (followTranscript) {
            transcript.gotoBottom();
        }
        if (requestedScrollRows < 0) {
            transcript.scrollUp(-requestedScrollRows);
        } else if (requestedScrollRows > 0) {
            transcript.scrollDown(requestedScrollRows);
        }

        List<String> lines = new ArrayList<>(state.rows());
        if (!showSidebar) {
            lines.add(transcript.view());
            lines.addAll(after);
        } else {
            int leftWidth = Math.max(1, mainWidth - 1);
            int rightWidth = Math.max(1, sidebarWidth - 1);
            List<String> mainAfter = after.subList(0, after.size() - 1);
            int panelRows = viewportRows + visualRows(mainAfter);
            List<String> sidebarLines = renderSidebar(state, panelRows, rightWidth);
            String[] leftRows = (transcript.view() + "\n" + String.join("\n", mainAfter)).split("\n", -1);
            StringBuilder combined = new StringBuilder();
            for (int row = 0; row < panelRows; row++) {
                String left = row < leftRows.length ? leftRows[row] : "";
                String right = row < sidebarLines.size() ? sidebarLines.get(row) : "";
                int leftLen = TextWidth.measureCellWidth(left);
                String leftPadded;
                if (leftLen < leftWidth) {
                    leftPadded = left + " ".repeat(leftWidth - leftLen);
                } else if (leftLen > leftWidth) {
                    leftPadded = Truncate.truncate(left, leftWidth, "");
                } else {
                    leftPadded = left;
                }
                int rightLen = TextWidth.measureCellWidth(right);
                String rightPadded;
                if (rightLen < rightWidth) {
                    rightPadded = right + " ".repeat(rightWidth - rightLen);
                } else if (rightLen > rightWidth) {
                    rightPadded = Truncate.truncate(right, rightWidth, "");
                } else {
                    rightPadded = right;
                }
                if (row > 0) combined.append('\n');
                combined.append(leftPadded).append(theme.borderSubtle("│")).append(rightPadded);
            }
            lines.add(combined.toString());
            lines.add(after.getLast());
        }
        return lines.stream().map(value -> clip(value, state.columns())).collect(Collectors.joining("\n"));
    }

    String transcriptContent(TerminalUiState state) {
        if (state.transcript().isEmpty()) {
            markdown.retain(Set.of());
            transcriptHits = List.of();
            return theme.muted("Start a task or use /commands.");
        }
        Set<String> assistantIds = new HashSet<>();
        state.transcript().stream()
                .filter(item -> item.kind() == TranscriptItem.Kind.ASSISTANT)
                .map(TranscriptItem::id)
                .forEach(assistantIds::add);
        markdown.retain(assistantIds);

        boolean showSidebar = state.sidebarVisible() && state.columns() >= 100;
        int mainWidth = showSidebar ? state.columns() - 42 : state.columns();
        int availableWidth = showSidebar ? Math.max(12, mainWidth - 1) : Math.max(12, mainWidth);

        StringBuilder content = new StringBuilder();
        List<TranscriptHit> hits = new ArrayList<>();
        TranscriptItem previous = null;
        int row = 0;
        for (TranscriptItem item : state.transcript()) {
            if (previous != null) {
                String separator = isCompactTool(previous) && isCompactTool(item) ? "\n" : "\n\n";
                content.append(separator);
                row += separator.length();
            }
            String rendered = transcriptItem(item, availableWidth);
            int startRow = row;
            content.append(rendered);
            int lineCount = lineCount(rendered);
            if (item.toggleable()) {
                hits.add(new TranscriptHit(startRow, startRow + lineCount - 1, item.id()));
            }
            row = startRow + lineCount - 1;
            previous = item;
        }
        transcriptHits = List.copyOf(hits);
        return content.toString();
    }

    private List<String> renderSidebar(TerminalUiState state, int height, int width) {
        List<String> lines = new ArrayList<>();
        if (state.session().isPresent()) {
            var sessionView = state.session().get();
            lines.add(theme.strong(clip(resolveSidebarTitle(state), width)));
            lines.add(theme.muted(clip(sessionView.summary().sessionId().value(), width)));
        } else {
            lines.add(theme.strong("Haifa Coding Agent"));
            lines.add(theme.muted("No active session"));
        }
        lines.add("");

        var footer = state.footer();
        if (!footer.project().isBlank()) {
            lines.add(theme.muted("📁 " + clip(footer.project(), width - 3)));
        }
        if (!footer.gitBranch().isBlank()) {
            lines.add(theme.muted("🌿 " + clip(footer.gitBranch(), width - 3)));
        }
        if (!footer.worktree().isBlank()) {
            lines.add(theme.muted("🌳 " + clip(footer.worktree(), width - 3)));
            if (!footer.worktreeBranch().isBlank()) {
                lines.add(theme.muted("  🌿 " + clip(footer.worktreeBranch(), width - 5)));
            }
        }
        lines.add("");

        lines.add(theme.focus("Resources"));
        List<String> meaningful = state.loadedResources().stream()
                .map(Tui4jTerminalView::sanitize)
                .filter(this::isMeaningfulResource)
                .toList();
        if (meaningful.isEmpty()) {
            lines.add(theme.muted("  • None loaded"));
        } else {
            for (String res : meaningful) {
                lines.add(theme.muted("  • " + clip(res, width - 4)));
            }
        }
        lines.add("");

        lines.add(theme.muted("• Haifa Agent v0.1.4-SNAPSHOT"));
        lines.add(theme.muted("• " + shortcuts.sidebarToggle() + " toggle"));

        while (lines.size() < height) {
            lines.add("");
        }
        return lines;
    }

    private String resolveSidebarTitle(TerminalUiState state) {
        if (state.session().isPresent()) {
            var sessionView = state.session().get();
            if (sessionView.activeRunTaskSummary().isPresent()
                    && !sessionView.activeRunTaskSummary().get().isBlank()) {
                return sessionView.activeRunTaskSummary().get().strip();
            }
            for (int i = state.transcript().size() - 1; i >= 0; i--) {
                TranscriptItem item = state.transcript().get(i);
                if (item.kind() == TranscriptItem.Kind.USER && !item.body().isBlank()) {
                    String prompt = item.body().lines().findFirst().orElse("").strip();
                    if (!prompt.isEmpty()) {
                        return prompt.length() > 80 ? prompt.substring(0, 79) + "…" : prompt;
                    }
                }
            }
            if (!sessionView.summary().displayName().isBlank()) {
                return sessionView.summary().displayName().strip();
            }
        }
        return "Haifa Coding Agent";
    }

    private List<String> lowerRegions(
            TerminalUiState state,
            Textarea editor,
            boolean newOutputPending,
            boolean compact,
            Duration activityElapsed,
            boolean secureInput,
            boolean isUpdate,
            String secureInputHint,
            boolean mouseReporting,
            int editorColumns) {
        List<String> lines = new ArrayList<>();
        // An open selector owns the keyboard, so no region above it may advertise its own Enter contract.
        boolean modal = state.selector().isPresent();
        if (!state.pending().isEmpty()) {
            lines.add(theme.queued("Pending · " + state.pending().size()));
            int pendingLimit = compact ? 1 : state.recoverableError().isPresent() ? 2 : 3;
            state.pending().stream()
                    .limit(pendingLimit)
                    .map(this::pending)
                    .map(theme::queued)
                    .forEach(lines::add);
        }
        state.waiting().ifPresent(waiting -> lines.addAll(waitingRegion(waiting, compact, editorColumns, modal)));
        state.recoverableError().ifPresent(value -> {
            TerminalRecovery recovery = TerminalRecovery.fromCode(value);
            java.util.function.Function<String, String> recoveryStyle =
                    recovery.category() == TerminalRecovery.Category.TERMINAL_CAPABILITY ? theme::queued : theme::error;
            lines.add(recoveryStyle.apply(recovery.displayTitle()));
            if (!compact)
                lines.add(recoveryStyle.apply(textWrapper.wrap("  " + recovery.action(), editorColumns, true)));
        });
        if (modal) {
            // One spacer row separates the modal card from the background regions it covers. It is a single space,
            // not an empty string, because the row budget counts the lines of every region.
            lines.add(" ");
            lines.addAll(selector(state.selector().get(), compact ? 1 : 4, editorColumns));
        } else {
            lines.add(theme.editor(stylePastePlaceholders(editor.view()), editorColumns, 3));
            if (!compact) {
                lines.add(promptMetaStrip(state, secureInput, isUpdate, secureInputHint, editorColumns));
            }
        }
        lines.add(renderBottomStatusLine(state, newOutputPending, activityElapsed, mouseReporting));
        return lines;
    }

    /**
     * Highlight OpenCode-style paste chips inside the textarea view: one chip for the block a stream shows while it is
     * received and for the summary it settles into. The buffer keeps the plain token text for
     * editing/atomic delete; only the rendered frame gets the warning chip.
     */
    private String stylePastePlaceholders(String editorView) {
        Matcher matcher = PASTE_PLACEHOLDER.matcher(editorView);
        if (!matcher.find()) {
            return editorView;
        }
        StringBuilder styled = new StringBuilder(editorView.length() + 32);
        matcher.reset();
        while (matcher.find()) {
            matcher.appendReplacement(styled, Matcher.quoteReplacement(theme.pasteChip(matcher.group())));
        }
        matcher.appendTail(styled);
        return styled.toString();
    }

    private List<String> waitingRegion(
            io.haifa.agent.application.coding.terminal.state.WaitingMessage waiting,
            boolean compact,
            int editorColumns,
            boolean modal) {
        List<String> lines = new ArrayList<>();
        String status;
        String hint;
        String actions;
        switch (waiting.phase()) {
            case EDITING -> {
                status = "Editing";
                hint = "save keeps Waiting · does not steer";
                actions = "  [Save enter]  [Discard esc]";
            }
            case SENDING -> {
                status = "Sending · Steer";
                hint = "submitted · waiting for model accept";
                actions = "  Waiting for model…";
            }
            default -> {
                status = "Waiting";
                hint = "hold until run finishes · follow-up";
                actions = "  [Send Now enter]  [Edit " + shortcuts.editWaiting() + "]  [Cancel esc]";
            }
        }
        lines.add(theme.pending("⠋ " + status + "  ") + theme.muted(hint));
        String body = sanitize(waiting.text());
        int maxLines = compact ? 1 : 3;
        String wrapped = textWrapper.wrap(body, Math.max(16, editorColumns - 2), true);
        String[] bodyLines = wrapped.split("\n", -1);
        for (int index = 0; index < Math.min(maxLines, bodyLines.length); index++) {
            String prefix = index == maxLines - 1 && bodyLines.length > maxLines ? "… " : "  ";
            String line = bodyLines[index];
            if (index == maxLines - 1 && bodyLines.length > maxLines) {
                line = line.length() > 1 ? line.substring(0, Math.max(1, line.length() - 1)) : line;
            }
            lines.add(theme.queued(prefix + line));
        }
        if (!compact && !modal) {
            lines.add(theme.muted(actions));
        }
        return lines;
    }

    private String promptMetaStrip(
            TerminalUiState state, boolean secureInput, boolean isUpdate, String secureInputHint, int editorColumns) {
        if (secureInput) {
            return theme.muted(editorHint(state, secureInput, isUpdate, secureInputHint));
        }
        boolean isShell =
                state.editorBuffer().startsWith("!") || state.editorBuffer().startsWith("!!");
        String mode = isShell ? (state.editorBuffer().startsWith("!!") ? "Shell (silent)" : "Shell") : "Normal";
        String model =
                state.footer().model().isBlank() ? "" : " · " + state.footer().model();
        String provider =
                state.footer().provider().isBlank() ? "" : " (" + state.footer().provider() + ")";
        String leftMeta = "  " + mode + model + provider;
        String hint = editorHint(state, false, false, null);
        int available = editorColumns;
        int leftWidth = TextWidth.measureCellWidth(leftMeta);
        int hintWidth = TextWidth.measureCellWidth(hint);
        if (leftWidth + hintWidth + 4 <= available) {
            int spaces = available - leftWidth - hintWidth;
            return theme.muted(leftMeta + " ".repeat(spaces) + hint);
        }
        return theme.muted(leftMeta);
    }

    private String renderBottomStatusLine(
            TerminalUiState state, boolean newOutputPending, Duration activityElapsed, boolean mouseReporting) {
        String alert = newOutputPending ? theme.queued("new output below") : "";
        // A modal selector owns Esc as "close", so the run interrupt affordance stays out of the bottom line.
        boolean modal = state.selector().isPresent();
        String left;
        if (state.currentRunId().isPresent()) {
            String act = timedActivityStatus(
                    state.status(), activityElapsed, state.activity().label());
            String interruptHint = "";
            if (!modal) {
                interruptHint = state.cancelArmed()
                        ? theme.warning(" · " + shortcuts.interrupt() + " again to interrupt")
                        : theme.muted(" · " + shortcuts.interrupt() + " interrupt");
            }
            left = theme.pending("⠋ " + act) + interruptHint;
            if (!alert.isEmpty()) {
                left = left + " · " + alert;
            }
        } else {
            String opStatus = sanitize(state.status().strip());
            if (opStatus.equalsIgnoreCase("COMPLETED")) opStatus = "Ready for follow-up";
            boolean hasOpFeedback = !opStatus.isBlank()
                    && !opStatus.equalsIgnoreCase("idle")
                    && !(state.recoverableError().isPresent() && opStatus.equalsIgnoreCase("Recovery required"));
            String path = state.footer().project().isBlank()
                    ? "Haifa Coding Agent"
                    : state.footer().project();
            if (hasOpFeedback) {
                left = theme.accent(opStatus);
                if (!alert.isEmpty()) {
                    left = left + " · " + alert;
                }
            } else if (!mouseReporting) {
                left = theme.muted("native selection · PageUp/PageDown scroll");
                if (!alert.isEmpty()) {
                    left = left + " · " + alert;
                }
            } else {
                left = theme.muted(path);
                if (!alert.isEmpty()) {
                    left = left + " · " + alert;
                }
            }
        }

        int available = Math.max(1, state.columns() - 1);
        String fullShortcuts = "["
                + shortcuts.toggleExpansion()
                + "] details  ["
                + shortcuts.modelCycle()
                + "] models  ["
                + shortcuts.commandPalette()
                + "] commands";
        String baseShortcuts = "[" + shortcuts.modelCycle() + "] models  [" + shortcuts.commandPalette() + "] commands";
        String shortcutsHint = fullShortcuts;
        int shortcutsLen = TextWidth.measureCellWidth(shortcutsHint);
        int baseShortcutsLen = TextWidth.measureCellWidth(baseShortcuts);
        int leftLen = TextWidth.measureCellWidth(left);
        String metrics = state.footer().metrics();
        int metricsLen = metrics.isBlank() ? 0 : TextWidth.measureCellWidth(metrics);

        List<String> rightParts = new ArrayList<>();
        if (!metrics.isBlank()) {
            if (leftLen + metricsLen + shortcutsLen + 5 <= available) {
                rightParts.add(metrics);
            } else if (leftLen + metricsLen + baseShortcutsLen + 5 <= available) {
                shortcutsHint = baseShortcuts;
                shortcutsLen = baseShortcutsLen;
                rightParts.add(metrics);
            }
        }
        if (rightParts.isEmpty() && shortcutsLen > baseShortcutsLen && leftLen + baseShortcutsLen + 2 <= available) {
            shortcutsHint = baseShortcuts;
            shortcutsLen = baseShortcutsLen;
        }
        rightParts.add(shortcutsHint);
        String right = theme.muted(String.join(" · ", rightParts));
        int rightLen = TextWidth.measureCellWidth(right);

        if (leftLen + rightLen + 2 <= available) {
            int spaces = available - leftLen - rightLen;
            return left + " ".repeat(spaces) + right;
        }

        right = theme.muted(shortcutsHint);
        rightLen = shortcutsLen;
        if (leftLen + rightLen + 2 <= available) {
            int spaces = available - leftLen - rightLen;
            return left + " ".repeat(spaces) + right;
        }

        int maxLeft = Math.max(10, available - rightLen - 2);
        if (maxLeft < leftLen && available > leftLen) {
            return left;
        }
        String truncatedLeft = Truncate.truncate(left, maxLeft, "…");
        int truncatedLeftLen = TextWidth.measureCellWidth(truncatedLeft);
        int spaces = Math.max(1, available - truncatedLeftLen - rightLen);
        return truncatedLeft + " ".repeat(spaces) + right;
    }

    private String transcriptItem(TranscriptItem item, int availableWidth) {
        String status = item.status().toLowerCase(Locale.ROOT);
        boolean collapsed = item.collapsible();
        if (item.kind() == TranscriptItem.Kind.USER) {
            return renderUserMessage(item, availableWidth);
        }
        if (item.kind() == TranscriptItem.Kind.ERROR) {
            return renderErrorMessage(item, availableWidth);
        }
        if (isToolLike(item)) {
            return renderToolItem(item, availableWidth);
        }
        String title = sanitize(
                switch (item.kind()) {
                    case USER -> "You";
                    case ASSISTANT -> item.title();
                    case TOOL, EXECUTION, SUMMARY -> glyph(item.status()) + " " + item.title() + durationSuffix(item);
                    case APPROVAL, CLARIFICATION -> item.title() + " [" + status + "]";
                    case RESOURCE -> "Resource · " + item.title() + " [" + status + "]";
                    case ERROR -> "Error · " + item.title() + " [" + status + "]";
                });
        if (item.kind() == TranscriptItem.Kind.ASSISTANT) {
            int bodyWidth = Math.max(12, availableWidth - 2);
            String rendered = markdown.render(item.id(), item.body(), bodyWidth);
            if ("FINAL".equals(item.status())) {
                title = "Assistant / Final response";
                title += "\n"
                        + theme.muted(clip(
                                shortcuts.leader() + " y copy · " + shortcuts.leader() + " a tool activity",
                                availableWidth));
            }
            String content = rendered.isEmpty() ? title : title + "\n" + indent(rendered);
            return style(item, content);
        }
        if ((item.kind() == TranscriptItem.Kind.APPROVAL || item.kind() == TranscriptItem.Kind.CLARIFICATION)
                && item.approvalDetails().isPresent()) {
            return style(item, approval(title, item.approvalDetails().orElseThrow(), item.expanded()));
        }
        if (collapsed) {
            String content = title + theme.muted("  ›");
            return style(item, content);
        }
        String body =
                item.expanded() ? item.body() : item.body().lines().limit(5).collect(Collectors.joining("\n"));
        String content = title + "\n" + body;
        return style(item, content);
    }

    private String renderToolItem(TranscriptItem item, int availableWidth) {
        boolean collapsed = item.collapsible();
        String header = toolHeader(item, availableWidth, collapsed);

        if (collapsed) {
            if (isUnknownStatus(item.status()) || isToolBlockingStatus(item.status())) {
                String details = item.body()
                        .lines()
                        .limit(2)
                        .map(value -> "  " + sanitize(value))
                        .collect(Collectors.joining("\n"));
                return details.isBlank() ? header : header + "\n" + details;
            }
            return header;
        }

        String visibleBody =
                item.toolDetails().map(details -> details.content()).orElse(item.body());
        String body =
                item.expanded() ? visibleBody : visibleBody.lines().limit(5).collect(Collectors.joining("\n"));
        String content = header + "\n" + renderToolBody(item, body);
        if (item.expanded()) {
            if (item.toolDetails().isPresent()) {
                var details = item.toolDetails().orElseThrow();
                content += "\n"
                        + theme.muted("  " + (details.expanded() ? "⌄ " : "› ") + "Technical details · "
                                + shortcuts.technicalDetails());
                if (details.expanded())
                    content += "\n"
                            + theme.muted(details.technical()
                                    .lines()
                                    .map(line -> "  " + sanitize(line))
                                    .collect(Collectors.joining("\n")));
            }
            String metadata = metadata(item);
            if (!metadata.isBlank()
                    && item.toolDetails().map(details -> details.expanded()).orElse(true)) {
                content = content + "\n" + theme.muted("  " + metadata);
            }
        }
        return content;
    }

    private String toolHeader(TranscriptItem item, int availableWidth, boolean collapsed) {
        String name = toolName(item.title());
        String target = toolTarget(item.title());
        String badge = theme.toolNameBadge(name);
        String glyph = item.exitCode().filter(code -> code != 0).isPresent()
                        && SUCCESS_STATUSES.contains(item.status().toUpperCase(Locale.ROOT))
                ? theme.muted("○")
                : styledGlyph(item.status());
        String metadata = auxiliaryMetadata(item, collapsed);
        String chevron = theme.muted(collapsed ? "›" : "⌄");

        int nameWidth = TextWidth.measureCellWidth(name);
        int targetWidth = TextWidth.measureCellWidth(target);
        int metadataWidth = TextWidth.measureCellWidth(metadata);
        int available = Math.max(1, availableWidth);
        int fixedLeft = 1 + 1 + nameWidth + 2 + (target.isEmpty() ? 0 : 1 + targetWidth);
        int rightWidth = (metadata.isEmpty() ? 0 : metadataWidth + 1) + 1;
        if (fixedLeft + 1 + rightWidth > available && !target.isEmpty()) {
            int maximumTarget = available - (1 + 1 + nameWidth + 2) - 1 - rightWidth;
            if (maximumTarget < 1) {
                int fixedWithoutTarget = 1 + 1 + nameWidth + 2;
                int gap = Math.max(0, available - fixedWithoutTarget - rightWidth);
                return glyph
                        + " "
                        + badge
                        + " ".repeat(gap)
                        + (metadata.isEmpty() ? "" : theme.muted(metadata) + " ")
                        + chevron;
            }
            target = Truncate.truncate(target, maximumTarget, "…");
            targetWidth = TextWidth.measureCellWidth(target);
            fixedLeft = 1 + 1 + nameWidth + 2 + 1 + targetWidth;
        }

        int gap = Math.max(0, available - fixedLeft - rightWidth);
        StringBuilder line = new StringBuilder();
        line.append(glyph).append(' ').append(badge);
        if (!target.isEmpty()) {
            line.append(' ').append(target);
        }
        line.append(" ".repeat(gap));
        if (!metadata.isEmpty()) {
            line.append(theme.muted(metadata)).append(' ');
        }
        line.append(chevron);
        return line.toString();
    }

    private static String toolName(String title) {
        int separator = title.indexOf(" · ");
        return separator > 0 ? sanitize(title.substring(0, separator)) : sanitize(title);
    }

    private static String toolTarget(String title) {
        int separator = title.indexOf(" · ");
        return separator > 0 ? sanitize(title.substring(separator + 3)) : "";
    }

    private static String auxiliaryMetadata(TranscriptItem item, boolean collapsed) {
        List<String> parts = new ArrayList<>();
        item.exitCode().filter(value -> value != 0).ifPresent(value -> parts.add("exit " + value));
        item.durationMillis().ifPresent(value -> parts.add(TerminalDurations.human(value)));
        return String.join(" · ", parts);
    }

    private String renderToolBody(TranscriptItem item, String body) {
        if (!isToolLike(item) || !TerminalDiffHighlighter.containsDiff(body)) {
            return body.lines()
                    .map(raw -> {
                        String clean = sanitize(raw);
                        if (clean.startsWith(ToolBodyLines.OUTCOME_PREFIX + " UNKNOWN")) {
                            return "  " + theme.warning(clean);
                        }
                        return "  " + clean;
                    })
                    .collect(Collectors.joining("\n"));
        }
        return TerminalDiffHighlighter.highlightToolBody(theme, body);
    }

    private String renderUserMessage(TranscriptItem item, int availableWidth) {
        boolean narrow = availableWidth < 40;
        int contentWidth = narrow ? Math.max(12, availableWidth - 2) : Math.max(16, availableWidth - 3);

        List<String> lines = new ArrayList<>();
        String headerText = "❯ You";
        lines.add(theme.userCardLine(padToWidth(headerText, contentWidth), narrow, true));

        String body = item.body().replace("\r\n", "\n").replace('\r', '\n');
        if (!body.isEmpty()) {
            for (String rawLine : body.split("\n", -1)) {
                String cleanLine = sanitize(rawLine);
                if (cleanLine.isEmpty()) {
                    lines.add(theme.userCardLine(" ".repeat(contentWidth), narrow, false));
                } else {
                    String wrapped = textWrapper.wrap(cleanLine, contentWidth, true);
                    for (String wLine : wrapped.split("\n", -1)) {
                        lines.add(theme.userCardLine(padToWidth(wLine, contentWidth), narrow, false));
                    }
                }
            }
        }
        return String.join("\n", lines);
    }

    private String renderErrorMessage(TranscriptItem item, int availableWidth) {
        boolean narrow = availableWidth < 40;
        int contentWidth = narrow ? Math.max(12, availableWidth - 2) : Math.max(16, availableWidth - 3);

        String status = item.status().toLowerCase(Locale.ROOT);
        String titleText = sanitize(item.title());
        String headerText = titleText.toLowerCase(Locale.ROOT).startsWith("error")
                ? "✖ " + titleText + " [" + status + "]"
                : "✖ Error · " + titleText + " [" + status + "]";

        List<String> lines = new ArrayList<>();
        String wrappedHeader = textWrapper.wrap(headerText, contentWidth, true);
        boolean isFirst = true;
        for (String hLine : wrappedHeader.split("\n", -1)) {
            lines.add(theme.errorCardLine(hLine, narrow, isFirst));
            isFirst = false;
        }

        String body = item.body().replace("\r\n", "\n").replace('\r', '\n');
        if (!body.isEmpty()) {
            String[] rawLines = body.split("\n", -1);
            int maxLines = item.expanded() ? rawLines.length : Math.min(rawLines.length, 5);
            for (int i = 0; i < maxLines; i++) {
                String cleanLine = sanitize(rawLines[i]);
                if (cleanLine.isEmpty()) {
                    lines.add(theme.errorCardLine("", narrow, false));
                } else {
                    String wrapped = textWrapper.wrap(cleanLine, contentWidth, true);
                    for (String wLine : wrapped.split("\n", -1)) {
                        lines.add(theme.errorCardLine(wLine, narrow, false));
                    }
                }
            }
            if (!item.expanded() && rawLines.length > maxLines) {
                String more = "… " + (rawLines.length - maxLines) + " more lines";
                lines.add(theme.errorCardLine(more, narrow, false));
            }
        }
        return String.join("\n", lines);
    }

    private static String padToWidth(String text, int targetWidth) {
        int width = TextWidth.measureCellWidth(text);
        if (width < targetWidth) {
            return text + " ".repeat(targetWidth - width);
        }
        if (width > targetWidth) {
            String truncated = Truncate.truncate(text, targetWidth, "");
            int truncatedWidth = TextWidth.measureCellWidth(truncated);
            return truncatedWidth < targetWidth ? truncated + " ".repeat(targetWidth - truncatedWidth) : truncated;
        }
        return text;
    }

    private static boolean isToolLike(TranscriptItem item) {
        return item.kind() == TranscriptItem.Kind.TOOL || item.kind() == TranscriptItem.Kind.EXECUTION;
    }

    private String approval(String title, ApprovalDetails details, boolean expanded) {
        StringBuilder content = new StringBuilder(title);
        content.append('\n').append("  ").append(sanitize(details.purpose()));
        content.append('\n').append(theme.muted("  " + sanitize(details.contentType())));
        List<String> contentLines = details.content().lines().toList();
        int visibleContentLines = expanded ? contentLines.size() : Math.min(contentLines.size(), 5);
        content.append('\n')
                .append(contentLines.stream()
                        .limit(visibleContentLines)
                        .map(line -> "    " + sanitize(line))
                        .collect(Collectors.joining("\n")));
        if (visibleContentLines < contentLines.size()) {
            content.append('\n')
                    .append(theme.muted(
                            "    … " + contentLines.size() + " lines · " + shortcuts.toggleExpansion() + " expand"));
        }
        if (!details.environment().isEmpty()) {
            content.append('\n').append("  ").append(theme.muted(facts(details.environment())));
        }
        if (!details.technical().isEmpty() || details.risk().isPresent()) {
            if (expanded) {
                content.append('\n').append(theme.muted("  Technical details"));
                details.risk()
                        .ifPresent(risk -> content.append('\n').append(theme.muted("    Risk: " + sanitize(risk))));
                details.technical().forEach(fact -> content.append('\n')
                        .append(theme.muted("    " + sanitize(fact.label() + ": " + fact.value()))));
            } else {
                content.append('\n')
                        .append(theme.muted("  Technical details · " + shortcuts.toggleExpansion() + " expand"));
            }
        }
        if (!details.allowedActions().isEmpty()) {
            content.append('\n')
                    .append(theme.muted("  Available actions: "
                            + details.allowedActions().stream()
                                    .map(ApprovalDetails::actionLabel)
                                    .collect(Collectors.joining(" / "))));
        }
        return content.toString();
    }

    private String facts(List<ApprovalDetails.Fact> facts) {
        return facts.stream()
                .map(fact -> sanitize(fact.label()) + "：" + sanitize(fact.value()))
                .collect(Collectors.joining(" · "));
    }

    private static String glyph(String status) {
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        if (UNKNOWN_STATUSES.contains(normalized)) return "?";
        if (SUCCESS_STATUSES.contains(normalized)) return "✓";
        if (NEUTRAL_STATUSES.contains(normalized)) return "–";
        if (ERROR_STATUSES.contains(normalized)) return "✗";
        return "●";
    }

    private String styledGlyph(String status) {
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        if (UNKNOWN_STATUSES.contains(normalized)) return theme.warning("?");
        if (SUCCESS_STATUSES.contains(normalized)) return theme.success("✓");
        if (NEUTRAL_STATUSES.contains(normalized)) return theme.muted("–");
        if (TOOL_ORDINARY_FAILURE_STATUSES.contains(normalized)) return theme.warning("!");
        if (isToolBlockingStatus(normalized)) return theme.error("✗");
        return theme.pending("●");
    }

    private static String durationSuffix(TranscriptItem item) {
        return item.durationMillis()
                .map(value -> " · " + TerminalDurations.human(value))
                .orElse("");
    }

    private String metadata(TranscriptItem item) {
        List<String> parts = new ArrayList<>();
        String machineName = item.toolName().orElse("");
        parts.add(theme.toolBadge(ToolCategory.fromToolName(machineName.isBlank() ? item.title() : machineName)));
        if (!machineName.isBlank()) parts.add(machineName);
        item.durationMillis().ifPresent(value -> parts.add("Duration " + TerminalDurations.human(value)));
        long lines = item.body().lines().filter(line -> !line.isBlank()).count();
        if (lines > 0) parts.add(lines + (lines == 1 ? " line" : " lines"));
        int bytes = item.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > 0) parts.add(bytes < 1024 ? bytes + " B" : String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0));
        return String.join(" · ", parts);
    }

    private boolean isCompactTool(TranscriptItem item) {
        return item.collapsible() && !isUnknownStatus(item.status()) && !isToolBlockingStatus(item.status());
    }

    private boolean isToolBlockingStatus(String status) {
        return TOOL_BLOCKING_STATUSES.contains(status.strip().toUpperCase(Locale.ROOT));
    }

    private boolean isUnknownStatus(String status) {
        return UNKNOWN_STATUSES.contains(status.strip().toUpperCase(Locale.ROOT));
    }

    private String indent(String value) {
        return java.util.Arrays.stream(value.split("\n", -1))
                .map(line -> "  " + line)
                .collect(Collectors.joining("\n"));
    }

    private String pending(PendingMessage message) {
        return "  [" + message.kind().name().toLowerCase(Locale.ROOT) + "] " + sanitize(message.summary());
    }

    private List<String> selector(TerminalSelector selector, int maximumVisibleOptions, int width) {
        List<String> lines = new ArrayList<>();
        lines.add(theme.focus("┌─ " + sanitize(selector.title())));
        if (selector.options().isEmpty()) {
            lines.add("│ No available items");
        }
        int start = Math.max(
                0,
                Math.min(
                        selector.selected() - maximumVisibleOptions / 2,
                        selector.options().size() - maximumVisibleOptions));
        int end = Math.min(selector.options().size(), start + maximumVisibleOptions);
        String category = "";
        boolean answersAQuestion = selector.kind().startsWith(CLARIFICATION_SELECTOR_PREFIX);
        for (int index = start; index < end; index++) {
            boolean selected = index == selector.selected();
            TerminalSelectorDetail detail = selector.detail(index).orElse(null);
            if (detail != null
                    && !detail.category().isBlank()
                    && !detail.category().equals(category)) {
                category = detail.category();
                lines.add(theme.muted("│ " + sanitize(category)));
            }
            StringBuilder option = new StringBuilder(selected ? "> " : "  ");
            option.append(sanitize(selector.options().get(index)));
            if (detail != null && !detail.badge().isBlank()) {
                option.append(" [").append(sanitize(detail.badge())).append(']');
            }
            String row = option.toString();
            if (selected && answersAQuestion) {
                row = enterAffordance(row, width);
            }
            lines.add("│ " + (selected ? theme.selected(row) : theme.unselected(row)));
            if (detail != null && !detail.description().isBlank()) {
                lines.add(theme.muted("│      " + selectorDescription(detail.description(), width)));
            }
        }
        if (selector.options().size() > maximumVisibleOptions) {
            lines.add(theme.muted(
                    "│ " + (start + 1) + "-" + end + " of " + selector.options().size()));
        }
        String hint;
        if ("auth-wait".equals(selector.kind())) {
            hint = "└─ c copy link · o reopen browser · Enter select · Esc cancel sign-in";
        } else if ("auth-device".equals(selector.kind())) {
            hint = "└─ c copy device code · l copy link · o open page · Enter select · Esc cancel sign-in";
        } else if (answersAQuestion) {
            hint = "└─ enter select · space mark · escape close (editor preserved)";
        } else {
            hint = "└─ enter select · escape close (editor preserved)";
        }
        lines.add(theme.muted(hint));
        return lines;
    }

    /**
     * Marks the focused answer row with the key that answers it, so an unmarked single-choice row cannot read as
     * "press Space first". The badge is right aligned, so a long label never pushes it out of the frame.
     */
    private static String enterAffordance(String row, int width) {
        String badge = " [enter]";
        int badgeWidth = TextWidth.measureCellWidth(badge);
        int available = Math.max(badgeWidth + 8, width - 2);
        int rowWidth = TextWidth.measureCellWidth(row);
        if (rowWidth + badgeWidth > available) {
            row = Truncate.truncate(row, Math.max(8, available - badgeWidth), "…");
            rowWidth = TextWidth.measureCellWidth(row);
        }
        return row + " ".repeat(Math.max(0, available - rowWidth - badgeWidth)) + badge;
    }

    /** An option's rationale stays on one physical row, so the modal never grows past the rows it covers. */
    private static String selectorDescription(String description, int width) {
        return Truncate.truncate(sanitize(description), Math.max(8, width - 7), "…");
    }

    private String timedActivityStatus(String status, Duration elapsed, String activityLabel) {
        long seconds = Math.max(1, elapsed.toSeconds());
        String duration = seconds < 60 ? seconds + "s" : "%dm %ds".formatted(seconds / 60, seconds % 60);
        String value = status.toUpperCase(Locale.ROOT) + " (" + duration + ")";
        if (status.equalsIgnoreCase("working") && !activityLabel.isBlank()) {
            return value + " · " + sanitize(activityLabel);
        }
        return value;
    }

    private String style(TranscriptItem item, String content) {
        if (item.kind() == TranscriptItem.Kind.USER) return theme.user(content);
        if (item.kind() == TranscriptItem.Kind.ERROR) return theme.error(content);
        if (item.kind() == TranscriptItem.Kind.ASSISTANT) return content;
        if (item.kind() == TranscriptItem.Kind.RESOURCE) return content;
        return statusStyle(item.status(), content);
    }

    private String statusStyle(String status, String content) {
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        if (SUCCESS_STATUSES.contains(normalized)) return theme.success(content);
        if (PENDING_STATUSES.contains(normalized)
                || TIMED_RUN_STATUSES.stream().anyMatch(candidate -> normalized.startsWith(candidate + " ("))) {
            return theme.pending(content);
        }
        if (UNKNOWN_STATUSES.contains(normalized)) return theme.pending(content);
        if (NEUTRAL_STATUSES.contains(normalized)) return theme.muted(content);
        if (ERROR_STATUSES.contains(normalized)) return theme.error(content);
        return content;
    }

    private String submitHint(TerminalUiState state) {
        if (state.waiting()
                .filter(value ->
                        value.phase() == io.haifa.agent.application.coding.terminal.state.WaitingMessage.Phase.EDITING)
                .isPresent()) {
            return "enter save";
        }
        if (state.waiting()
                .filter(value ->
                        value.phase() == io.haifa.agent.application.coding.terminal.state.WaitingMessage.Phase.WAITING)
                .isPresent()) {
            return state.editorBuffer().isBlank() ? "enter send now" : "enter replace waiting";
        }
        return state.currentRunId().isPresent() ? "enter waiting" : "enter send";
    }

    private String editorHint(TerminalUiState state, boolean secureInput, boolean isUpdate, String secureInputHint) {
        if (secureInput) {
            if (secureInputHint != null && !secureInputHint.isBlank()) {
                return secureInputHint;
            }
            return isUpdate ? "Enter update · Esc cancel" : "Enter submit · Esc cancel";
        }
        if (state.selector().isPresent()) return "enter select · escape close";
        if (state.waiting()
                .filter(value ->
                        value.phase() == io.haifa.agent.application.coding.terminal.state.WaitingMessage.Phase.EDITING)
                .isPresent()) {
            return "enter save · esc discard edit";
        }
        if (state.waiting()
                .filter(value ->
                        value.phase() == io.haifa.agent.application.coding.terminal.state.WaitingMessage.Phase.SENDING)
                .isPresent()) {
            return "steer pending · " + shortcuts.interrupt() + " interrupt";
        }
        if (state.waiting()
                .filter(value ->
                        value.phase() == io.haifa.agent.application.coding.terminal.state.WaitingMessage.Phase.WAITING)
                .isPresent()) {
            return "enter send now · "
                    + shortcuts.editWaiting()
                    + " edit · esc cancel · "
                    + shortcuts.followUp()
                    + " follow-up · "
                    + shortcuts.interrupt()
                    + " interrupt";
        }
        if (state.currentRunId().isPresent()) {
            return "enter waiting · "
                    + shortcuts.followUp()
                    + " follow-up · "
                    + shortcuts.newline()
                    + " newline · "
                    + shortcuts.interrupt()
                    + " interrupt";
        }
        return "enter send · " + shortcuts.newline() + " newline · tab complete";
    }

    private boolean isMeaningfulResource(String value) {
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return !normalized.isEmpty()
                && !normalized.equals("none")
                && !normalized.equals("loaded resources: none")
                && !normalized.equals("resources: none");
    }

    private void addMeaningful(List<String> target, String value) {
        String normalized = meaningful(value);
        if (!normalized.isEmpty() && !normalized.endsWith(": —")) target.add(normalized);
    }

    private String meaningful(String value) {
        return value == null ? "" : sanitize(value.strip());
    }

    private int visualRows(List<String> regions) {
        return regions.stream().mapToInt(value -> (int) value.lines().count()).sum();
    }

    private static int lineCount(String value) {
        int lines = 1;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '\n') lines++;
        }
        return lines;
    }

    private String clip(String region, int columns) {
        int safeColumns = Math.max(1, columns - 1);
        return java.util.Arrays.stream(region.split("\n", -1))
                .map(line -> Truncate.truncate(line, safeColumns, ""))
                .collect(Collectors.joining("\n"));
    }

    static String sanitize(String value) {
        StringBuilder safe = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint == '\t') {
                safe.append("    ");
            } else if (!Character.isISOControl(codePoint)) {
                safe.appendCodePoint(codePoint);
            }
        });
        return safe.toString();
    }
}
