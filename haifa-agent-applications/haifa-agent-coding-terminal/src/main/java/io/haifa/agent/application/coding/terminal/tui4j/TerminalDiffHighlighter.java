package io.haifa.agent.application.coding.terminal.tui4j;

import io.haifa.agent.application.coding.terminal.state.ToolBodyLines;
import java.util.ArrayList;
import java.util.List;

/**
 * High-performance, zero-IO Unified Diff syntax highlighter for tool and execution output.
 *
 * <p>Strictly preserves all original characters and indentation: ANSI-stripped text
 * strictly matches the raw input text. Degrades safely to plain text in NoColor mode.
 */
final class TerminalDiffHighlighter {
    private TerminalDiffHighlighter() {}

    /**
     * Determines whether the given line is a valid Unified Diff hunk header (e.g. @@ -1,3 +1,3 @@).
     */
    static boolean isHunkHeader(String line) {
        if (line == null || !line.startsWith("@@ -")) {
            return false;
        }
        int secondAt = line.indexOf(" @@", 4);
        return secondAt > 0 && line.substring(4, secondAt).contains(" +");
    }

    /**
     * Determines whether the given text block appears to contain Unified Diff hunks or headers.
     */
    static boolean containsDiff(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean hasHunkHeader = false;
        boolean hasOldFile = false;
        boolean hasNewFile = false;
        boolean hasAddOrDel = false;

        for (String rawLine : text.split("\n", -1)) {
            if (isHunkHeader(rawLine)) {
                hasHunkHeader = true;
            } else if (rawLine.startsWith("--- ")) {
                hasOldFile = true;
            } else if (rawLine.startsWith("+++ ")) {
                hasNewFile = true;
            } else if ((rawLine.startsWith("+") && !rawLine.startsWith("+++"))
                    || (rawLine.startsWith("-") && !rawLine.startsWith("---"))) {
                hasAddOrDel = true;
            }
        }
        return (hasHunkHeader && hasAddOrDel) || (hasOldFile && hasNewFile && (hasHunkHeader || hasAddOrDel));
    }

    /**
     * Determines whether a single line is a Unified Diff line.
     * Note: context lines starting with a space are not diff lines (they are unchanged code).
     */
    static boolean isDiffLine(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        return line.startsWith("--- ")
                || line.startsWith("+++ ")
                || isHunkHeader(line)
                || (line.startsWith("+") && !line.startsWith("+++"))
                || (line.startsWith("-") && !line.startsWith("---"))
                || line.startsWith("\\ No newline");
    }

    /**
     * Highlights a single line of Unified Diff output, preserving exact leading whitespace.
     */
    static String highlightLine(Tui4jTerminalTheme theme, String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        if (line.startsWith("--- ") || line.startsWith("+++ ")) {
            return theme.diffHeader(line);
        }
        if (isHunkHeader(line)) {
            return theme.diffHunk(line);
        }
        if (line.startsWith("+") && !line.startsWith("+++")) {
            return theme.diffAdded(line);
        }
        if (line.startsWith("-") && !line.startsWith("---")) {
            return theme.diffRemoved(line);
        }
        if (line.startsWith("\\ No newline")) {
            return theme.muted(line);
        }
        return line;
    }

    /**
     * Highlights an entire tool body containing a Unified Diff, distinguishing diff hunks from
     * regular output and preserving context lines without miscoloring them.
     */
    static String highlightToolBody(Tui4jTerminalTheme theme, String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        boolean inHunk = false;
        List<String> renderedLines = new ArrayList<>();
        for (String raw : body.split("\n", -1)) {
            String clean = Tui4jTerminalView.sanitize(raw);
            if (isHunkHeader(clean)) {
                inHunk = true;
                renderedLines.add("  " + theme.diffHunk(clean));
            } else if (clean.startsWith("--- ") || clean.startsWith("+++ ")) {
                inHunk = false;
                renderedLines.add("  " + theme.diffHeader(clean));
            } else if (inHunk) {
                if (clean.startsWith("+") && !clean.startsWith("+++")) {
                    renderedLines.add("  " + theme.diffAdded(clean));
                } else if (clean.startsWith("-") && !clean.startsWith("---")) {
                    renderedLines.add("  " + theme.diffRemoved(clean));
                } else if (clean.startsWith(" ") || clean.isEmpty()) {
                    renderedLines.add("  " + clean);
                } else if (clean.startsWith("\\ No newline")) {
                    renderedLines.add("  " + theme.muted(clean));
                } else {
                    inHunk = false;
                    if (clean.startsWith(ToolBodyLines.OUTCOME_PREFIX + " UNKNOWN")) {
                        renderedLines.add("  " + theme.warning(clean));
                    } else {
                        renderedLines.add("  " + clean);
                    }
                }
            } else {
                if (clean.startsWith(ToolBodyLines.OUTCOME_PREFIX + " UNKNOWN")) {
                    renderedLines.add("  " + theme.warning(clean));
                } else {
                    renderedLines.add("  " + clean);
                }
            }
        }
        return String.join("\n", renderedLines);
    }
}
