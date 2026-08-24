package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.ansi.Truncate;
import com.williamcallahan.tui4j.compat.bubbles.textarea.Textarea;
import com.williamcallahan.tui4j.compat.bubbles.viewport.Viewport;
import io.haifa.agent.application.coding.terminal.state.PendingMessage;
import io.haifa.agent.application.coding.terminal.state.TerminalRecovery;
import io.haifa.agent.application.coding.terminal.state.TerminalSelector;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
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
            "PENDING",
            "QUEUED",
            "RECOVERING",
            "REQUESTED",
            "RUNNING",
            "STARTED",
            "STREAMING",
            "SUBMITTING",
            "THINKING",
            "VERIFYING",
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
        return render(state, transcript, editor, followTranscript, newOutputPending, Duration.ZERO, 0);
    }

    String render(
            TerminalUiState state,
            Viewport transcript,
            Textarea editor,
            boolean followTranscript,
            boolean newOutputPending,
            Duration activityElapsed) {
        return render(state, transcript, editor, followTranscript, newOutputPending, activityElapsed, 0);
    }

    String render(
            TerminalUiState state,
            Viewport transcript,
            Textarea editor,
            boolean followTranscript,
            boolean newOutputPending,
            Duration activityElapsed,
            int requestedScrollRows) {
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
        List<String> before = header(state, compact);
        List<String> after =
                lowerRegions(state, editor, newOutputPending && !followTranscript, compact, activityElapsed);
        int viewportRows = Math.max(1, state.rows() - visualRows(before) - visualRows(after));
        transcript.setWidth(state.columns());
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
        lines.addAll(before);
        lines.add(transcript.view());
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

        StringBuilder content = new StringBuilder();
        TranscriptItem previous = null;
        for (TranscriptItem item : state.transcript()) {
            if (previous != null) {
                content.append(isCompactTool(previous) && isCompactTool(item) ? '\n' : "\n\n");
            }
            content.append(transcriptItem(item, Math.max(12, state.columns() - 2)));
            previous = item;
        }
        return content.toString();
    }

    private List<String> header(TerminalUiState state, boolean compact) {
        List<String> lines = new ArrayList<>();
        lines.add(theme.accent(state.header()) + theme.muted("  v0.1"));
        if (compact) {
            lines.add(
                    theme.muted("tab complete · " + submitHint(state) + " · " + shortcuts.interrupt() + " interrupt"));
        } else {
            lines.add(theme.muted(shortcuts.interrupt()
                    + " interrupt · "
                    + shortcuts.clear()
                    + " clear · "
                    + shortcuts.toggleExpansion()
                    + " expand/collapse · tab complete · "
                    + submitHint(state)));
            if (state.currentRunId().isPresent()) {
                lines.add(theme.muted(shortcuts.followUp()
                        + " follow-up · "
                        + shortcuts.restoreQueuedMessage()
                        + " restore queued message"));
            }
            lines.add(theme.muted("mouse wheel/page up/down scroll · shift+drag select"));
        }
        resources(state).ifPresent(lines::add);
        return lines;
    }

    private List<String> lowerRegions(
            TerminalUiState state,
            Textarea editor,
            boolean newOutputPending,
            boolean compact,
            Duration activityElapsed) {
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
        status(state, newOutputPending, activityElapsed).ifPresent(lines::add);
        state.recoverableError().ifPresent(value -> {
            TerminalRecovery recovery = TerminalRecovery.fromCode(value);
            java.util.function.Function<String, String> recoveryStyle =
                    recovery.category() == TerminalRecovery.Category.TERMINAL_CAPABILITY ? theme::queued : theme::error;
            lines.add(recoveryStyle.apply(recovery.displayTitle()));
            if (!compact) lines.add(recoveryStyle.apply("  " + recovery.action()));
        });
        state.selector()
                .ifPresentOrElse(
                        selector -> lines.addAll(selector(selector, compact ? 1 : 4)), () -> lines.add(editor.view()));
        lines.add(theme.focus(editorHint(state)));
        var footer = state.footer();
        List<String> workspace = new ArrayList<>();
        if (!footer.model().isBlank()) addMeaningful(workspace, "model: " + footer.model());
        if (!footer.project().isBlank()) addMeaningful(workspace, "cwd: " + footer.project());
        if (!footer.gitBranch().isBlank()) addMeaningful(workspace, "git: " + footer.gitBranch());
        if (!workspace.isEmpty()) lines.add(theme.muted(String.join(" · ", workspace)));
        List<String> context = new ArrayList<>();
        addMeaningful(context, footer.runStatus());
        addMeaningful(context, footer.session());
        if (!compact) {
            addMeaningful(context, footer.metrics());
        }
        if (!context.isEmpty()) lines.add(theme.muted(String.join(" · ", context)));
        return lines;
    }

    private String transcriptItem(TranscriptItem item, int bodyWidth) {
        String status = item.status().toLowerCase(Locale.ROOT);
        String title = sanitize(
                switch (item.kind()) {
                    case USER -> "You";
                    case ASSISTANT -> item.title();
                    case TOOL -> "Tool · " + item.title() + " [" + status + "]";
                    case EXECUTION -> "Execution · " + item.title() + " [" + status + "]";
                    case APPROVAL -> item.title() + " [" + status + "]";
                    case RESOURCE -> "Resource · " + item.title() + " [" + status + "]";
                    case ERROR -> "Error · " + item.title() + " [" + status + "]";
                });
        if (item.kind() == TranscriptItem.Kind.ASSISTANT) {
            String rendered = markdown.render(item.id(), item.body(), bodyWidth);
            String content = rendered.isEmpty() ? title : title + "\n" + indent(rendered);
            return style(item, content);
        }
        if (isCollapsedTool(item)) {
            String content = title + theme.muted(" · " + shortcuts.toggleExpansion() + " expand");
            if (isErrorStatus(item.status())) {
                String details = item.body()
                        .lines()
                        .limit(2)
                        .map(value -> "  " + sanitize(value))
                        .collect(Collectors.joining("\n"));
                if (!details.isBlank()) content = content + "\n" + details;
            }
            return style(item, content);
        }
        String body =
                item.expanded() ? item.body() : item.body().lines().limit(5).collect(Collectors.joining("\n"));
        String content =
                title + "\n" + body.lines().map(value -> "  " + sanitize(value)).collect(Collectors.joining("\n"));
        return style(item, content);
    }

    private boolean isCompactTool(TranscriptItem item) {
        return isCollapsedTool(item) && !isErrorStatus(item.status());
    }

    private boolean isCollapsedTool(TranscriptItem item) {
        return !item.expanded()
                && (item.kind() == TranscriptItem.Kind.TOOL || item.kind() == TranscriptItem.Kind.EXECUTION);
    }

    private boolean isErrorStatus(String status) {
        return ERROR_STATUSES.contains(status.strip().toUpperCase(Locale.ROOT));
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

    private java.util.Optional<String> resources(TerminalUiState state) {
        List<String> values = state.loadedResources().stream()
                .map(this::sanitize)
                .filter(this::isMeaningfulResource)
                .toList();
        if (values.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(theme.muted("resources · " + String.join(" · ", values)));
    }

    private java.util.Optional<String> status(
            TerminalUiState state, boolean newOutputPending, Duration activityElapsed) {
        List<String> parts = new ArrayList<>();
        String value = sanitize(state.status().strip());
        if (state.currentRunId().isPresent() && TIMED_RUN_STATUSES.contains(value.toUpperCase(Locale.ROOT))) {
            value = timedActivityStatus(value, activityElapsed, state.activity().label());
        }
        if (!value.equalsIgnoreCase("idle")
                && !(state.recoverableError().isPresent() && value.equalsIgnoreCase("Recovery required"))) {
            parts.add(value);
        }
        if (newOutputPending) parts.add("new output below");
        if (parts.isEmpty()) return java.util.Optional.empty();
        String content = String.join(" · ", parts);
        if (parts.size() == 1 && newOutputPending) {
            return java.util.Optional.of(theme.queued(content));
        }
        return java.util.Optional.of(statusStyle(value, content));
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

    private String editorHint(TerminalUiState state) {
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

    private String sanitize(String value) {
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
