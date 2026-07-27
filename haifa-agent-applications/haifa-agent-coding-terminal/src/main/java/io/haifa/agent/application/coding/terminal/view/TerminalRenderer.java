package io.haifa.agent.application.coding.terminal.view;

import io.haifa.agent.application.coding.terminal.state.PendingMessage;
import io.haifa.agent.application.coding.terminal.state.TerminalSelector;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.coding.terminal.state.TranscriptItem;
import java.util.ArrayList;
import java.util.List;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/** Semantic renderer matching the reviewed low-fi prototype's single-column information order. */
public final class TerminalRenderer {
    private static final AttributedStyle MUTED =
            AttributedStyle.DEFAULT.foreground(AttributedStyle.BLACK | AttributedStyle.BRIGHT);
    private static final AttributedStyle ACCENT =
            AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold();
    private static final AttributedStyle USER = AttributedStyle.DEFAULT
            .background(AttributedStyle.BLACK | AttributedStyle.BRIGHT)
            .foreground(AttributedStyle.WHITE);
    private static final AttributedStyle ERROR =
            AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold();

    public TerminalView render(TerminalUiState state) {
        int width = Math.max(20, state.columns());
        List<AttributedString> lines = new ArrayList<>();

        line(lines, state.header() + "  v0.1", ACCENT, width);
        line(lines, "esc interrupt  ·  ctrl+c clear  ·  ctrl+o expand tools", MUTED, width);
        line(lines, "tab lists /commands and @files  ·  alt+enter follow-up  ·  alt+up restore", MUTED, width);
        lines.add(AttributedString.EMPTY);

        state.loadedResources().forEach(value -> line(lines, value, MUTED, width));
        lines.add(AttributedString.EMPTY);

        for (TranscriptItem item : state.transcript()) {
            renderTranscript(lines, item, width);
        }

        if (!state.pending().isEmpty()) {
            line(lines, "Pending messages", ACCENT, width);
            for (PendingMessage message : state.pending()) {
                line(
                        lines,
                        "[" + message.kind().name().toLowerCase(java.util.Locale.ROOT) + "] " + message.summary(),
                        MUTED,
                        width);
            }
            line(lines, "alt+up / restore queued messages", MUTED, width);
        }

        line(lines, "* " + state.status() + statusHelp(state.status()), MUTED, width);
        state.recoverableError().ifPresent(value -> line(lines, value, ERROR, width));
        if (state.columns() < 60 || state.rows() < 16) {
            line(lines, "Terminal is compact; widen to 60x16 for full details.", ERROR, width);
        }

        int cursorRow;
        int cursorColumn;
        if (state.selector().isPresent()) {
            renderSelector(lines, state.selector().orElseThrow(), width);
            cursorRow = Math.max(0, lines.size() - 2);
            cursorColumn = 2;
        } else {
            line(lines, "┌─ Message " + "─".repeat(Math.max(1, width - 12)), ACCENT, width);
            List<String> editorLines = state.editorBuffer().isEmpty()
                    ? List.of("Type a message; use Tab for /commands or @files")
                    : state.editorBuffer().lines().toList();
            for (String editorLine : editorLines) {
                line(lines, "│ " + editorLine, AttributedStyle.DEFAULT, width);
            }
            line(lines, "└─ Enter sends · Tab completes · Shift+Enter newline · Alt+Enter follows up", MUTED, width);
            cursorRow = Math.max(0, lines.size() - 2);
            cursorColumn = Math.min(width - 1, 2 + currentLineCursor(state.editorBuffer(), state.editorCursor()));
        }

        lines.add(AttributedString.EMPTY);
        var footer = state.footer();
        line(lines, footer.project() + " (" + footer.gitBranch() + ") · " + footer.session(), MUTED, width);
        line(
                lines,
                footer.metrics() + " · " + footer.provider() + " · " + footer.model() + " · " + footer.runStatus()
                        + " · " + footer.sandbox(),
                MUTED,
                width);
        return new TerminalView(lines, cursorRow, cursorColumn);
    }

    private static void renderTranscript(List<AttributedString> lines, TranscriptItem item, int width) {
        AttributedStyle titleStyle = item.kind() == TranscriptItem.Kind.ERROR
                ? ERROR
                : item.kind() == TranscriptItem.Kind.USER ? USER : ACCENT;
        String title = item.kind() == TranscriptItem.Kind.USER
                ? " You "
                : item.title() + "  [" + item.status().toLowerCase(java.util.Locale.ROOT) + "]";
        line(lines, title, titleStyle, width);
        String body = item.expanded() ? item.body() : collapsed(item.body());
        body.lines().forEach(value -> line(lines, "  " + value, AttributedStyle.DEFAULT, width));
        if (!item.expanded() && item.body().lines().count() > 5) {
            line(lines, "  … ctrl+o to expand", MUTED, width);
        }
        lines.add(AttributedString.EMPTY);
    }

    private static void renderSelector(List<AttributedString> lines, TerminalSelector selector, int width) {
        line(
                lines,
                "┌─ " + selector.title() + " "
                        + "─".repeat(Math.max(1, width - selector.title().length() - 5)),
                ACCENT,
                width);
        if (selector.options().isEmpty()) {
            line(lines, "│ No available items", MUTED, width);
        }
        for (int index = 0; index < selector.options().size(); index++) {
            line(
                    lines,
                    "│ " + (index == selector.selected() ? "> " : "  ")
                            + selector.options().get(index),
                    index == selector.selected() ? ACCENT : AttributedStyle.DEFAULT,
                    width);
        }
        line(lines, "└─ enter select · escape close (editor buffer preserved)", MUTED, width);
    }

    private static String collapsed(String body) {
        return body.lines().limit(5).collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String statusHelp(String status) {
        return status.equalsIgnoreCase("Working") ? "... / escape to interrupt" : "";
    }

    private static int currentLineCursor(String buffer, int cursor) {
        int lastNewline = buffer.lastIndexOf('\n', Math.max(0, cursor - 1));
        return cursor - lastNewline - 1;
    }

    private static void line(List<AttributedString> target, String value, AttributedStyle style, int width) {
        String safe = sanitize(value);
        AttributedStringBuilder builder = new AttributedStringBuilder().style(style);
        builder.append(safe);
        AttributedString attributed = builder.toAttributedString();
        target.add(attributed.columnLength() <= width ? attributed : attributed.columnSubSequence(0, width));
    }

    private static String sanitize(String value) {
        StringBuilder safe = new StringBuilder(value.length());
        value.codePoints()
                .filter(codePoint -> codePoint == '\t' || !Character.isISOControl(codePoint))
                .forEach(safe::appendCodePoint);
        return safe.toString();
    }
}
