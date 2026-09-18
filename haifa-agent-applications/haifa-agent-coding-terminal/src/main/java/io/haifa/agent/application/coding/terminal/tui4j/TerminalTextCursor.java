package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.ansi.TextWidth;
import com.williamcallahan.tui4j.compat.x.ansi.GraphemeCluster;
import com.williamcallahan.tui4j.compat.x.ansi.Method;
import java.util.ArrayList;
import java.util.List;

/** Grapheme-safe editor cursor operations expressed as UTF-16 boundaries for Java string slicing. */
final class TerminalTextCursor {
    private TerminalTextCursor() {}

    static int clamp(String value, int cursor) {
        int candidate = Math.max(0, Math.min(cursor, value.length()));
        int boundary = 0;
        for (Cluster cluster : clusters(value, 0, value.length())) {
            if (cluster.end() > candidate) break;
            boundary = cluster.end();
        }
        return boundary;
    }

    static int previous(String value, int cursor) {
        int checked = clamp(value, cursor);
        int previous = 0;
        for (Cluster cluster : clusters(value, 0, checked)) {
            if (cluster.end() >= checked) return cluster.start();
            previous = cluster.end();
        }
        return previous;
    }

    static int next(String value, int cursor) {
        int checked = clamp(value, cursor);
        for (Cluster cluster : clusters(value, checked, value.length())) {
            return cluster.end();
        }
        return value.length();
    }

    static int lineStart(String value, int cursor) {
        int checked = clamp(value, cursor);
        int newline = value.lastIndexOf('\n', Math.max(0, checked - 1));
        return newline < 0 ? 0 : newline + 1;
    }

    static int lineEnd(String value, int cursor) {
        int checked = clamp(value, cursor);
        int newline = value.indexOf('\n', checked);
        return newline < 0 ? value.length() : newline;
    }

    static int vertical(String value, int cursor, int delta) {
        int checked = clamp(value, cursor);
        int start = lineStart(value, checked);
        int desiredCells = TextWidth.measureCellWidth(value.substring(start, checked));
        if (delta < 0) {
            if (start == 0) return checked;
            int previousEnd = start - 1;
            return atCell(value, lineStart(value, previousEnd), previousEnd, desiredCells);
        }
        int end = lineEnd(value, checked);
        if (end == value.length()) return checked;
        int nextStart = end + 1;
        return atCell(value, nextStart, lineEnd(value, nextStart), desiredCells);
    }

    static String backspace(String value, int cursor) {
        int checked = clamp(value, cursor);
        int previous = previous(value, checked);
        return value.substring(0, previous) + value.substring(checked);
    }

    static String delete(String value, int cursor) {
        int checked = clamp(value, cursor);
        int next = next(value, checked);
        return value.substring(0, checked) + value.substring(next);
    }

    private static int atCell(String value, int start, int end, int desiredCells) {
        int cursor = start;
        int cells = 0;
        for (Cluster cluster : clusters(value, start, end)) {
            int nextCells = cells + cluster.width();
            if (nextCells > desiredCells) break;
            cursor = cluster.end();
            cells = nextCells;
        }
        return cursor;
    }

    private static List<Cluster> clusters(String value, int start, int end) {
        List<Cluster> result = new ArrayList<>();
        int offset = start;
        while (offset < end) {
            String remaining = value.substring(offset, end);
            GraphemeCluster.StringResult cluster =
                    GraphemeCluster.getFirstGraphemeClusterString(remaining, Method.GRAPHEME_WIDTH);
            if (cluster.cluster().isEmpty()) {
                int next = value.offsetByCodePoints(offset, 1);
                result.add(new Cluster(offset, next, TextWidth.measureCellWidth(value.substring(offset, next))));
                offset = next;
            } else {
                int next = offset + cluster.cluster().length();
                result.add(new Cluster(offset, next, Math.max(0, cluster.width())));
                offset = next;
            }
        }
        return result;
    }

    private record Cluster(int start, int end, int width) {}
}
