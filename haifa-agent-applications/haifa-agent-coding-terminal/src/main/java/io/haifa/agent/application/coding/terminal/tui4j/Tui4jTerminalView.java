package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.ansi.Truncate;
import com.williamcallahan.tui4j.compat.bubbles.textarea.Textarea;
import com.williamcallahan.tui4j.compat.bubbles.viewport.Viewport;
import io.haifa.agent.application.coding.terminal.state.PendingMessage;
import io.haifa.agent.application.coding.terminal.state.TerminalRecovery;
import io.haifa.agent.application.coding.terminal.state.TerminalSelector;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.coding.terminal.state.TranscriptItem;
import java.util.ArrayList;
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
            "CANCELLING", "PENDING", "QUEUED", "REQUESTED", "RUNNING", "STARTED", "STREAMING", "WAITING", "WORKING");
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

    String render(
            TerminalUiState state,
            Viewport transcript,
            Textarea editor,
            boolean followTranscript,
            boolean newOutputPending) {
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
        List<String> after = lowerRegions(state, editor, newOutputPending && !followTranscript, compact);
        int viewportRows = Math.max(1, state.rows() - visualRows(before) - visualRows(after));
        transcript.setWidth(state.columns());
        transcript.setHeight(viewportRows);
        if (followTranscript) {
            transcript.gotoBottom();
        }

        List<String> lines = new ArrayList<>(state.rows());
        lines.addAll(before);
        lines.add(transcript.view());
        lines.addAll(after);
        return lines.stream().map(value -> clip(value, state.columns())).collect(Collectors.joining("\n"));
    }

    String transcriptContent(TerminalUiState state) {
        if (state.transcript().isEmpty()) {
            return theme.muted("Start a task or use /commands.");
        }
        return state.transcript().stream().map(this::transcriptItem).collect(Collectors.joining("\n"));
    }

    private List<String> header(TerminalUiState state, boolean compact) {
        List<String> lines = new ArrayList<>();
        lines.add(theme.accent(state.header()) + theme.muted("  v0.1"));
        if (compact) {
            lines.add(theme.muted("tab complete · " + submitHint(state) + " · esc interrupt"));
        } else {
            lines.add(theme.muted("esc interrupt · ctrl+c clear · ctrl+o tools · tab complete · " + submitHint(state)));
            if (state.currentRunId().isPresent()) {
                lines.add(theme.muted("alt/option+enter follow-up · alt/option+up restore queued message"));
            }
        }
        resources(state).ifPresent(lines::add);
        return lines;
    }

    private List<String> lowerRegions(
            TerminalUiState state, Textarea editor, boolean newOutputPending, boolean compact) {
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
        status(state, newOutputPending).ifPresent(lines::add);
        state.recoverableError().ifPresent(value -> {
            TerminalRecovery recovery = TerminalRecovery.fromCode(value);
            java.util.function.Function<String, String> recoveryStyle =
                    recovery.category() == TerminalRecovery.Category.TERMINAL_CAPABILITY ? theme::queued : theme::error;
            lines.add(recoveryStyle.apply(recovery.category().label() + " · " + recovery.code()));
            if (!compact) lines.add(recoveryStyle.apply("  " + recovery.action()));
        });
        state.selector()
                .ifPresentOrElse(
                        selector -> lines.addAll(selector(selector, compact ? 1 : 4)), () -> lines.add(editor.view()));
        lines.add(theme.focus(editorHint(state)));
        var footer = state.footer();
        List<String> context = new ArrayList<>();
        addMeaningful(context, footer.runStatus());
        addMeaningful(context, footer.project());
        addMeaningful(context, footer.session());
        if (!compact) {
            addMeaningful(context, footer.metrics());
            addGitIfReal(context, footer.gitBranch());
        }
        lines.add(theme.muted(String.join(" · ", context)));
        return lines;
    }

    private String transcriptItem(TranscriptItem item) {
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
        String body =
                item.expanded() ? item.body() : item.body().lines().limit(5).collect(Collectors.joining("\n"));
        String content =
                title + "\n" + body.lines().map(value -> "  " + sanitize(value)).collect(Collectors.joining("\n"));
        if (!item.expanded()
                && (item.kind() == TranscriptItem.Kind.TOOL || item.kind() == TranscriptItem.Kind.EXECUTION)) {
            content = content + "\n  ctrl+o expand";
        }
        return style(item, content) + "\n";
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
            String option = "│ " + (index == selector.selected() ? "> " : "  ")
                    + sanitize(selector.options().get(index));
            lines.add(index == selector.selected() ? theme.focus(option) : option);
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

    private java.util.Optional<String> status(TerminalUiState state, boolean newOutputPending) {
        List<String> parts = new ArrayList<>();
        String value = sanitize(state.status().strip());
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
        if (PENDING_STATUSES.contains(normalized)) return theme.pending(content);
        if (ERROR_STATUSES.contains(normalized)) return theme.error(content);
        return content;
    }

    private String submitHint(TerminalUiState state) {
        return state.currentRunId().isPresent() ? "enter steer" : "enter send";
    }

    private String editorHint(TerminalUiState state) {
        if (state.selector().isPresent()) return "enter select · escape close";
        if (state.currentRunId().isPresent()) {
            return "enter steer · alt/option+enter follow-up · shift+enter/ctrl+j newline · esc interrupt";
        }
        return "enter send · shift+enter/ctrl+j newline · tab complete";
    }

    private boolean isMeaningfulResource(String value) {
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return !normalized.isEmpty()
                && !normalized.equals("none")
                && !normalized.equals("loaded resources: none")
                && !normalized.equals("resources: none");
    }

    private void addGitIfReal(List<String> target, String value) {
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.equals("git: unavailable") || normalized.equals("git: via safe read model")) return;
        addMeaningful(target, value);
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
