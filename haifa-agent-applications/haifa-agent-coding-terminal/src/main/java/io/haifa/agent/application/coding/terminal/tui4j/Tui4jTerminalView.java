package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.ansi.TextWidth;
import com.williamcallahan.tui4j.ansi.Truncate;
import com.williamcallahan.tui4j.compat.bubbles.textarea.Textarea;
import com.williamcallahan.tui4j.compat.bubbles.viewport.Viewport;
import io.haifa.agent.application.coding.terminal.state.ApprovalDetails;
import io.haifa.agent.application.coding.terminal.state.PendingMessage;
import io.haifa.agent.application.coding.terminal.state.TerminalDurations;
import io.haifa.agent.application.coding.terminal.state.TerminalRecovery;
import io.haifa.agent.application.coding.terminal.state.TerminalSelector;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.coding.terminal.state.ToolBodyLines;
import io.haifa.agent.application.coding.terminal.state.TranscriptItem;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Composes the reviewed Prototype's single-column regions using tui4j components. */
final class Tui4jTerminalView {
    private static final int MIN_COLUMNS = 60;
    private static final int MIN_ROWS = 16;
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
    private static final Set<String> UNKNOWN_STATUSES = Set.of("OUTCOME_UNKNOWN", "UNKNOWN_OUTCOME");
    private static final long COLLAPSED_DURATION_HIDE_MILLIS = 10_000L;

    private final Tui4jTerminalTheme theme = new Tui4jTerminalTheme();
    private final IncrementalTerminalMarkdownRenderer markdown = new IncrementalTerminalMarkdownRenderer(theme);
    private final TerminalShortcutProfile shortcuts;

    Tui4jTerminalView() {
        this(TerminalShortcutProfile.standard());
    }

    Tui4jTerminalView(TerminalShortcutProfile shortcuts) {
        this.shortcuts = java.util.Objects.requireNonNull(shortcuts, "shortcuts must not be null");
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
                secureInputHint);
        int viewportRows = Math.max(1, state.rows() - visualRows(after));
        transcript.setWidth(showSidebar ? Math.max(12, mainWidth - 1) : Math.max(12, mainWidth));
        transcript.setHeight(viewportRows);
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
        } else {
            int leftWidth = Math.max(1, mainWidth - 1);
            int rightWidth = Math.max(1, sidebarWidth - 1);
            List<String> sidebarLines = renderSidebar(state, viewportRows, rightWidth);
            String[] leftRows = transcript.view().split("\n", -1);
            StringBuilder combined = new StringBuilder();
            for (int row = 0; row < viewportRows; row++) {
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
                combined.append(leftPadded).append(theme.muted("│")).append(rightPadded);
            }
            lines.add(combined.toString());
        }
        lines.addAll(after);
        return lines.stream().map(value -> clip(value, state.columns())).collect(Collectors.joining("\n"));
    }

    String transcriptContent(TerminalUiState state) {
        if (state.transcript().isEmpty()) {
            markdown.retain(Set.of());
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

        StringBuilder content = new StringBuilder();
        TranscriptItem previous = null;
        for (TranscriptItem item : state.transcript()) {
            if (previous != null) {
                content.append(isCompactTool(previous) && isCompactTool(item) ? '\n' : "\n\n");
            }
            content.append(transcriptItem(item, Math.max(12, mainWidth - 2)));
            previous = item;
        }
        return content.toString();
    }

    private List<String> renderSidebar(TerminalUiState state, int height, int width) {
        List<String> lines = new ArrayList<>();
        if (state.session().isPresent()) {
            var summary = state.session().get().summary();
            lines.add(theme.strong(clip(summary.displayName(), width)));
            lines.add(theme.muted(clip(summary.sessionId().value(), width)));
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

        lines.add(theme.muted("• Haifa Agent v0.1.2"));
        lines.add(theme.muted("• " + shortcuts.sidebarToggle() + " toggle"));

        while (lines.size() < height) {
            lines.add("");
        }
        return lines;
    }

    private List<String> lowerRegions(
            TerminalUiState state,
            Textarea editor,
            boolean newOutputPending,
            boolean compact,
            Duration activityElapsed,
            boolean secureInput,
            boolean isUpdate,
            String secureInputHint) {
        List<String> lines = new ArrayList<>();
        if (!state.pending().isEmpty()) {
            lines.add(theme.queued("Pending · " + state.pending().size()));
            int pendingLimit = compact ? 1 : state.recoverableError().isPresent() ? 2 : 3;
            state.pending().stream()
                    .limit(pendingLimit)
                    .map(this::pending)
                    .map(theme::queued)
                    .forEach(lines::add);
        }
        state.recoverableError().ifPresent(value -> {
            TerminalRecovery recovery = TerminalRecovery.fromCode(value);
            java.util.function.Function<String, String> recoveryStyle =
                    recovery.category() == TerminalRecovery.Category.TERMINAL_CAPABILITY ? theme::queued : theme::error;
            lines.add(recoveryStyle.apply(recovery.displayTitle()));
            if (!compact) lines.add(recoveryStyle.apply("  " + recovery.action()));
        });
        if (state.selector().isPresent()) {
            lines.addAll(selector(state.selector().get(), compact ? 1 : 4));
        } else {
            lines.add(theme.editor(editor.view(), Math.max(1, state.columns() - 1), 3));
            if (!compact) {
                lines.add(promptMetaStrip(state, secureInput, isUpdate, secureInputHint));
            }
        }
        lines.add(renderBottomStatusLine(state, newOutputPending, activityElapsed));
        return lines;
    }

    private String promptMetaStrip(
            TerminalUiState state, boolean secureInput, boolean isUpdate, String secureInputHint) {
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
        int available = Math.max(1, state.columns() - 1);
        int leftWidth = TextWidth.measureCellWidth(leftMeta);
        int hintWidth = TextWidth.measureCellWidth(hint);
        if (leftWidth + hintWidth + 4 <= available) {
            int spaces = available - leftWidth - hintWidth;
            return theme.muted(leftMeta + " ".repeat(spaces) + hint);
        }
        return theme.muted(leftMeta);
    }

    private String renderBottomStatusLine(TerminalUiState state, boolean newOutputPending, Duration activityElapsed) {
        String alert = newOutputPending ? theme.queued("new output below") : "";
        String left;
        if (state.currentRunId().isPresent()) {
            String act = timedActivityStatus(
                    state.status(), activityElapsed, state.activity().label());
            left = theme.pending("⠋ " + act) + theme.muted(" · " + shortcuts.interrupt() + " interrupt");
            if (!alert.isEmpty()) {
                left = left + " · " + alert;
            }
        } else {
            String opStatus = sanitize(state.status().strip());
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
            } else {
                left = theme.muted(path);
                if (!alert.isEmpty()) {
                    left = left + " · " + alert;
                }
            }
        }

        int available = Math.max(1, state.columns() - 1);
        String shortcutsHint = "[" + shortcuts.modelCycle() + "] models  [" + shortcuts.commandPalette() + "] commands";
        int shortcutsLen = TextWidth.measureCellWidth(shortcutsHint);
        int leftLen = TextWidth.measureCellWidth(left);

        List<String> rightParts = new ArrayList<>();
        if (!state.footer().metrics().isBlank()) {
            int metricsLen = TextWidth.measureCellWidth(state.footer().metrics());
            if (leftLen + metricsLen + shortcutsLen + 5 <= available) {
                rightParts.add(state.footer().metrics());
            }
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

    private String transcriptItem(TranscriptItem item, int bodyWidth) {
        String status = item.status().toLowerCase(Locale.ROOT);
        boolean collapsed = item.collapsible();
        String title = sanitize(
                switch (item.kind()) {
                    case USER -> "You";
                    case ASSISTANT -> item.title();
                    case TOOL, EXECUTION, SUMMARY ->
                        glyph(item.status()) + " " + item.title() + durationSuffix(item, collapsed);
                    case APPROVAL -> item.title() + " [" + status + "]";
                    case RESOURCE -> "Resource · " + item.title() + " [" + status + "]";
                    case ERROR -> "Error · " + item.title() + " [" + status + "]";
                });
        if (item.kind() == TranscriptItem.Kind.ASSISTANT) {
            String rendered = markdown.render(item.id(), item.body(), bodyWidth);
            String content = rendered.isEmpty() ? title : title + "\n" + indent(rendered);
            return style(item, content);
        }
        if (item.kind() == TranscriptItem.Kind.APPROVAL
                && item.approvalDetails().isPresent()) {
            return style(item, approval(title, item.approvalDetails().orElseThrow(), item.expanded()));
        }
        if (collapsed) {
            StringBuilder content =
                    new StringBuilder(title).append(theme.muted(" · " + shortcuts.toggleExpansion() + " expand"));
            if (isErrorStatus(item.status()) || isUnknownStatus(item.status())) {
                String details = item.body()
                        .lines()
                        .limit(2)
                        .map(value -> "  " + sanitize(value))
                        .collect(Collectors.joining("\n"));
                if (!details.isBlank()) content.append('\n').append(details);
            } else if (isToolLike(item)) {
                String preview = previewLine(item.body(), bodyWidth);
                if (!preview.isBlank()) content.append('\n').append(theme.muted("  " + preview));
            }
            return style(item, content.toString());
        }
        String body =
                item.expanded() ? item.body() : item.body().lines().limit(5).collect(Collectors.joining("\n"));
        String content =
                title + "\n" + body.lines().map(value -> "  " + sanitize(value)).collect(Collectors.joining("\n"));
        if (item.expanded() && isToolLike(item)) {
            String metadata = metadata(item);
            if (!metadata.isBlank()) content = content + "\n" + theme.muted("  " + metadata);
        }
        return style(item, content);
    }

    private static String previewLine(String body, int bodyWidth) {
        int available = Math.max(16, bodyWidth - 2);
        return body.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .filter(line -> !ToolBodyLines.isStructural(line))
                .findFirst()
                .map(line -> Truncate.truncate(sanitize(line), available, "…"))
                .orElse("");
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
                            "    …共 " + contentLines.size() + " 行 · " + shortcuts.toggleExpansion() + " 展开"));
        }
        if (!details.environment().isEmpty()) {
            content.append('\n').append("  ").append(theme.muted(facts(details.environment())));
        }
        if (!details.technical().isEmpty() || details.risk().isPresent()) {
            if (expanded) {
                content.append('\n').append(theme.muted("  技术细节"));
                details.risk().ifPresent(risk -> content.append('\n').append(theme.muted("    风险: " + sanitize(risk))));
                details.technical().forEach(fact -> content.append('\n')
                        .append(theme.muted("    " + sanitize(fact.label() + ": " + fact.value()))));
            } else {
                content.append('\n').append(theme.muted("  技术细节（可选） · " + shortcuts.toggleExpansion() + " 展开"));
            }
        }
        if (!details.allowedActions().isEmpty()) {
            content.append('\n').append(theme.muted("  可用操作: " + String.join(" / ", details.allowedActions())));
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
        if (ERROR_STATUSES.contains(normalized)) return "✗";
        return "●";
    }

    private static String durationSuffix(TranscriptItem item, boolean collapsed) {
        return item.durationMillis()
                .filter(value -> !collapsed || value > COLLAPSED_DURATION_HIDE_MILLIS)
                .map(value -> " · " + TerminalDurations.human(value))
                .orElse("");
    }

    private static String metadata(TranscriptItem item) {
        List<String> parts = new ArrayList<>();
        item.durationMillis().ifPresent(value -> parts.add("Duration " + TerminalDurations.human(value)));
        long lines = item.body().lines().filter(line -> !line.isBlank()).count();
        if (lines > 0) parts.add(lines + (lines == 1 ? " line" : " lines"));
        int bytes = item.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > 0) parts.add(bytes < 1024 ? bytes + " B" : String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0));
        return String.join(" · ", parts);
    }

    private boolean isCompactTool(TranscriptItem item) {
        return item.collapsible() && !isErrorStatus(item.status()) && !isUnknownStatus(item.status());
    }

    private boolean isErrorStatus(String status) {
        return ERROR_STATUSES.contains(status.strip().toUpperCase(Locale.ROOT));
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

    private List<String> selector(TerminalSelector selector, int maximumVisibleOptions) {
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
        for (int index = start; index < end; index++) {
            boolean selected = index == selector.selected();
            String option =
                    (selected ? "> " : "  ") + sanitize(selector.options().get(index));
            lines.add("│ " + (selected ? theme.selected(option) : theme.unselected(option)));
        }
        if (selector.options().size() > maximumVisibleOptions) {
            lines.add(theme.muted(
                    "│ " + (start + 1) + "-" + end + " of " + selector.options().size()));
        }
        lines.add(theme.muted("└─ enter select · escape close (editor preserved)"));
        return lines;
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
        if (ERROR_STATUSES.contains(normalized)) return theme.error(content);
        return content;
    }

    private String submitHint(TerminalUiState state) {
        return state.currentRunId().isPresent() ? "enter steer" : "enter send";
    }

    private String editorHint(TerminalUiState state, boolean secureInput, boolean isUpdate, String secureInputHint) {
        if (secureInput) {
            if (secureInputHint != null && !secureInputHint.isBlank()) {
                return secureInputHint;
            }
            return isUpdate ? "enter update · escape cancel" : "enter submit · escape cancel";
        }
        if (state.selector().isPresent()) return "enter select · escape close";
        if (state.currentRunId().isPresent()) {
            return "enter steer · "
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

    private String clip(String region, int columns) {
        int safeColumns = Math.max(1, columns - 1);
        return java.util.Arrays.stream(region.split("\n", -1))
                .map(line -> Truncate.truncate(line, safeColumns, ""))
                .collect(Collectors.joining("\n"));
    }

    private static String sanitize(String value) {
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
