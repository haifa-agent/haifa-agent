package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.ansi.TextWidth;
import com.williamcallahan.tui4j.compat.x.ansi.GraphemeCluster;
import com.williamcallahan.tui4j.compat.x.ansi.Method;
import com.williamcallahan.tui4j.compat.x.ansi.Strip;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** ANSI-aware conversion between rendered terminal cells and copyable transcript text. */
final class TerminalScreenCells {
    static final int DEFAULT_MAX_COPY_CHARACTERS = 65_536;
    private static final char ESCAPE = '\u001B';
    private static final String SELECTION_ON = "\u001B[7m";
    private static final String SELECTION_OFF = "\u001B[27m";
    private static final String RESET = "\u001B[0m";

    private TerminalScreenCells() {}

    static String plainText(String styledContent) {
        return sanitizeControls(Strip.strip(styledContent));
    }

    static int lineCount(String styledContent) {
        return styledContent.split("\\n", -1).length;
    }

    static Optional<String> selectedText(String styledContent, TerminalTextSelection.Range range) {
        return selectedText(styledContent, range, DEFAULT_MAX_COPY_CHARACTERS);
    }

    static Optional<String> selectedText(
            String styledContent, TerminalTextSelection.Range range, int maximumCharacters) {
        if (maximumCharacters < 1) throw new IllegalArgumentException("maximumCharacters must be positive");
        List<String> lines = plainLines(styledContent);
        if (!valid(range, lines.size())) return Optional.empty();

        int startRow = range.start().row();
        int endRow = range.end().row();
        StringBuilder selected = new StringBuilder();
        for (int row = startRow; row <= endRow; row++) {
            String line = lines.get(row);
            int from = row == startRow ? indexAtCell(line, range.start().column(), false) : 0;
            int to = row == endRow ? indexAtCell(line, range.end().column(), true) : line.length();
            if (to > from) selected.append(line, from, to);
            if (row < endRow) selected.append('\n');
            if (selected.length() > maximumCharacters) return Optional.empty();
        }
        if (selected.isEmpty()) return Optional.empty();
        return Optional.of(selected.toString());
    }

    static String highlight(String styledContent, TerminalTextSelection.Range range) {
        String[] styledLines = styledContent.split("\\n", -1);
        List<String> plainLines = plainLines(styledContent);
        if (!valid(range, plainLines.size())) return styledContent;

        List<String> highlighted = new ArrayList<>(styledLines.length);
        for (int row = 0; row < styledLines.length; row++) {
            if (row < range.start().row() || row > range.end().row()) {
                highlighted.add(styledLines[row]);
                continue;
            }
            String plain = plainLines.get(row);
            int from = row == range.start().row()
                    ? indexAtCell(plain, range.start().column(), false)
                    : 0;
            int to = row == range.end().row() ? indexAtCell(plain, range.end().column(), true) : plain.length();
            highlighted.add(highlightPlainRange(styledLines[row], from, to));
        }
        return String.join("\n", highlighted);
    }

    private static boolean valid(TerminalTextSelection.Range range, int lineCount) {
        return lineCount > 0 && range.start().row() < lineCount && range.end().row() < lineCount;
    }

    private static List<String> plainLines(String styledContent) {
        String[] styledLines = styledContent.split("\\n", -1);
        List<String> result = new ArrayList<>(styledLines.length);
        for (String line : styledLines) result.add(plainText(line));
        return result;
    }

    private static int indexAtCell(String line, int requestedCell, boolean includeCell) {
        int cell = 0;
        int offset = 0;
        while (offset < line.length()) {
            GraphemeCluster.StringResult cluster =
                    GraphemeCluster.getFirstGraphemeClusterString(line.substring(offset), Method.GRAPHEME_WIDTH);
            String value = cluster.cluster();
            if (value.isEmpty()) {
                int next = line.offsetByCodePoints(offset, 1);
                value = line.substring(offset, next);
            }
            int nextOffset = offset + value.length();
            int width = Math.max(0, TextWidth.measureCellWidth(value));
            int nextCell = cell + width;
            if (requestedCell < nextCell || requestedCell == cell) {
                return includeCell ? nextOffset : offset;
            }
            offset = nextOffset;
            cell = nextCell;
        }
        return line.length();
    }

    private static String highlightPlainRange(String styled, int fromPlainIndex, int toPlainIndex) {
        if (toPlainIndex <= fromPlainIndex) return styled;
        int start = styledIndexAtPlainBoundary(styled, fromPlainIndex, true);
        int end = styledIndexAtPlainBoundary(styled, toPlainIndex, false);
        if (end <= start) return styled;
        String selected = styled.substring(start, end).replace(RESET, RESET + SELECTION_ON);
        return styled.substring(0, start) + SELECTION_ON + selected + SELECTION_OFF + styled.substring(end);
    }

    private static int styledIndexAtPlainBoundary(String styled, int targetPlainIndex, boolean consumeAnsi) {
        int styledIndex = 0;
        int plainIndex = 0;
        while (styledIndex < styled.length()) {
            if (plainIndex == targetPlainIndex && (!consumeAnsi || styled.charAt(styledIndex) != ESCAPE)) {
                return styledIndex;
            }
            if (styled.charAt(styledIndex) == ESCAPE) {
                if (plainIndex == targetPlainIndex && !consumeAnsi) return styledIndex;
                styledIndex = escapeSequenceEnd(styled, styledIndex);
                continue;
            }
            int codePoint = styled.codePointAt(styledIndex);
            styledIndex += Character.charCount(codePoint);
            plainIndex += Character.charCount(codePoint);
        }
        return styled.length();
    }

    private static int escapeSequenceEnd(String value, int start) {
        if (start + 1 >= value.length()) return value.length();
        char kind = value.charAt(start + 1);
        if (kind == '[') {
            int index = start + 2;
            while (index < value.length()) {
                char current = value.charAt(index++);
                if (current >= 0x40 && current <= 0x7E) return index;
            }
            return value.length();
        }
        if (kind == ']') {
            int index = start + 2;
            while (index < value.length()) {
                char current = value.charAt(index++);
                if (current == '\u0007') return index;
                if (current == ESCAPE && index < value.length() && value.charAt(index) == '\\') return index + 1;
            }
            return value.length();
        }
        return Math.min(value.length(), start + 2);
    }

    private static String sanitizeControls(String value) {
        StringBuilder sanitized = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\t' || (codePoint >= 0x20 && codePoint != 0x7F)) {
                sanitized.appendCodePoint(codePoint);
            }
        });
        return sanitized.toString();
    }
}
