package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.ansi.Truncate;
import com.williamcallahan.tui4j.compat.bubbles.textarea.Textarea;
import com.williamcallahan.tui4j.compat.bubbles.viewport.Viewport;
import io.haifa.agent.application.coding.terminal.state.PendingMessage;
import io.haifa.agent.application.coding.terminal.state.TerminalSelector;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.coding.terminal.state.TranscriptItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Composes the reviewed Prototype's single-column regions using tui4j components. */
final class Tui4jTerminalView {
    private static final int MIN_COLUMNS = 60;
    private static final int MIN_ROWS = 16;

    String render(
            TerminalUiState state,
            Viewport transcript,
            Textarea editor,
            boolean viewportAtBottom,
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
        List<String> after = lowerRegions(state, editor, newOutputPending && !viewportAtBottom, compact);
        int viewportRows = Math.max(1, state.rows() - visualRows(before) - visualRows(after));
        transcript.setWidth(state.columns());
        transcript.setHeight(viewportRows);

        List<String> lines = new ArrayList<>(state.rows());
        lines.addAll(before);
        lines.add(transcript.view());
        lines.addAll(after);
        return lines.stream().map(value -> clip(value, state.columns())).collect(Collectors.joining("\n"));
    }

    String transcriptContent(TerminalUiState state) {
        if (state.transcript().isEmpty()) {
            return "Conversation\n  Start a task or use /commands.";
        }
        return state.transcript().stream().map(this::transcriptItem).collect(Collectors.joining("\n"));
    }

    private List<String> header(TerminalUiState state, boolean compact) {
        List<String> lines = new ArrayList<>();
        lines.add(state.header().toUpperCase(Locale.ROOT) + "  v0.1");
        if (compact) {
            lines.add("Startup help  tab complete · enter send · esc interrupt");
        } else {
            lines.add("Startup help  esc interrupt · ctrl+c clear · ctrl+o expand tools");
            lines.add("               tab /commands or @files · alt+enter follow-up · alt+up restore");
        }
        lines.add("Loaded resources  " + String.join(" · ", state.loadedResources()));
        lines.add("Diagnostics  tui4j terminal · Resize limitation recorded");
        return lines;
    }

    private List<String> lowerRegions(
            TerminalUiState state, Textarea editor, boolean newOutputPending, boolean compact) {
        List<String> lines = new ArrayList<>();
        if (!state.pending().isEmpty()) {
            lines.add("Pending messages");
            int pendingLimit = compact ? 1 : state.recoverableError().isPresent() ? 2 : 3;
            state.pending().stream().limit(pendingLimit).map(this::pending).forEach(lines::add);
        } else {
            lines.add("Pending messages  none");
        }
        lines.add("Status  " + state.status() + (newOutputPending ? " · new output below" : ""));
        state.recoverableError().ifPresent(value -> lines.add("Error  " + value));
        lines.add("Widgets above  none");
        state.selector()
                .ifPresentOrElse(
                        selector -> lines.addAll(selector(selector, compact ? 1 : 4)), () -> lines.add(editor.view()));
        lines.add("Widgets below  none");
        var footer = state.footer();
        lines.add("Footer  Enter sends · Shift+Enter newline · Esc interrupts");
        if (compact) {
            lines.add(footer.project() + " · " + footer.session() + " · " + footer.runStatus());
        } else {
            lines.add(footer.project() + " (" + footer.gitBranch() + ") · " + footer.session());
            lines.add(footer.metrics() + " · " + footer.provider() + " · " + footer.model() + " · " + footer.runStatus()
                    + " · " + footer.sandbox());
        }
        return lines;
    }

    private String transcriptItem(TranscriptItem item) {
        String title = item.kind() == TranscriptItem.Kind.USER
                ? "You"
                : item.title() + " [" + item.status().toLowerCase(Locale.ROOT) + "]";
        String body =
                item.expanded() ? item.body() : item.body().lines().limit(5).collect(Collectors.joining("\n"));
        return title + "\n" + body.lines().map(value -> "  " + sanitize(value)).collect(Collectors.joining("\n"))
                + "\n";
    }

    private String pending(PendingMessage message) {
        return "  [" + message.kind().name().toLowerCase(Locale.ROOT) + "] " + sanitize(message.summary());
    }

    private List<String> selector(TerminalSelector selector, int maximumVisibleOptions) {
        List<String> lines = new ArrayList<>();
        lines.add("┌─ " + sanitize(selector.title()));
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
            lines.add("│ " + (index == selector.selected() ? "> " : "  ")
                    + sanitize(selector.options().get(index)));
        }
        if (selector.options().size() > maximumVisibleOptions) {
            lines.add(
                    "│ " + (start + 1) + "-" + end + " of " + selector.options().size());
        }
        lines.add("└─ enter select · escape close (editor preserved)");
        return lines;
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
        value.codePoints()
                .filter(codePoint -> codePoint == '\t' || !Character.isISOControl(codePoint))
                .forEach(safe::appendCodePoint);
        return safe.toString();
    }
}
