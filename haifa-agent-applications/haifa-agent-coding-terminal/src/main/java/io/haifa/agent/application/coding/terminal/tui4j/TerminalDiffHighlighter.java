package io.haifa.agent.application.coding.terminal.tui4j;

/**
 * High-performance, zero-IO Unified Diff syntax highlighter for tool and execution output.
 *
 * <p>Strictly preserves all original characters and indentation: ANSI-stripped text
 * strictly matches the raw input text. Degrades safely to plain text in NoColor mode.
 */
final class TerminalDiffHighlighter {
    private TerminalDiffHighlighter() {}

    /**
     * Determines whether the given text block appears to contain Unified Diff hunks or headers.
     */
    static boolean containsDiff(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean hasHunkHeader = false;
        boolean hasFileHeader = false;
        boolean hasAddOrDel = false;

        for (String rawLine : text.split("\n", -1)) {
            String trimmed = rawLine.stripLeading();
            if (trimmed.startsWith("@@ ") && trimmed.contains(" @@")) {
                hasHunkHeader = true;
            } else if (trimmed.startsWith("--- ") || trimmed.startsWith("+++ ")) {
                hasFileHeader = true;
            } else if ((trimmed.startsWith("+") && !trimmed.startsWith("+++"))
                    || (trimmed.startsWith("-") && !trimmed.startsWith("---"))) {
                hasAddOrDel = true;
            }
        }
        return (hasHunkHeader && hasAddOrDel) || (hasFileHeader && hasAddOrDel);
    }

    /**
     * Determines whether a single line is a Unified Diff line.
     */
    static boolean isDiffLine(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        String trimmed = line.stripLeading();
        return trimmed.startsWith("--- ")
                || trimmed.startsWith("+++ ")
                || (trimmed.startsWith("@@ ") && trimmed.contains(" @@"))
                || (trimmed.startsWith("+") && !trimmed.startsWith("+++"))
                || (trimmed.startsWith("-") && !trimmed.startsWith("---"))
                || trimmed.startsWith("\\ No newline");
    }

    /**
     * Highlights a single line of Unified Diff output, preserving exact leading whitespace.
     */
    static String highlightLine(Tui4jTerminalTheme theme, String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        String trimmed = line.stripLeading();
        if (trimmed.startsWith("--- ")) {
            return theme.diffHeader(line);
        }
        if (trimmed.startsWith("+++ ")) {
            return theme.diffHeader(line);
        }
        if (trimmed.startsWith("@@ ") && trimmed.contains(" @@")) {
            return theme.diffHunk(line);
        }
        if (trimmed.startsWith("+") && !trimmed.startsWith("+++")) {
            return theme.diffAdded(line);
        }
        if (trimmed.startsWith("-") && !trimmed.startsWith("---")) {
            return theme.diffRemoved(line);
        }
        if (trimmed.startsWith("\\ No newline")) {
            return theme.muted(line);
        }
        return line;
    }
}
