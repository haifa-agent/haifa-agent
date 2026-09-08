package io.haifa.agent.application.coding.terminal.tui4j;

import java.util.Objects;
import java.util.Optional;

/** Application-owned transcript selection expressed in rendered text-cell coordinates. */
final class TerminalTextSelection {
    private Point anchor;
    private Point active;
    private boolean selecting;

    void start(Point point) {
        anchor = Objects.requireNonNull(point, "point must not be null");
        active = point;
        selecting = true;
    }

    void extend(Point point) {
        if (anchor == null) return;
        active = Objects.requireNonNull(point, "point must not be null");
    }

    void finish(Point point) {
        if (anchor == null) return;
        active = Objects.requireNonNull(point, "point must not be null");
        selecting = false;
    }

    void clear() {
        anchor = null;
        active = null;
        selecting = false;
    }

    boolean selecting() {
        return selecting;
    }

    Optional<Range> range() {
        if (anchor == null || active == null) return Optional.empty();
        return Optional.of(new Range(anchor, active));
    }

    record Point(int row, int column) implements Comparable<Point> {
        Point {
            if (row < 0) throw new IllegalArgumentException("row must not be negative");
            if (column < 0) throw new IllegalArgumentException("column must not be negative");
        }

        @Override
        public int compareTo(Point other) {
            int rowOrder = Integer.compare(row, other.row);
            return rowOrder != 0 ? rowOrder : Integer.compare(column, other.column);
        }
    }

    record Range(Point start, Point end) {
        Range {
            Objects.requireNonNull(start, "start must not be null");
            Objects.requireNonNull(end, "end must not be null");
            if (start.compareTo(end) > 0) {
                Point originalStart = start;
                start = end;
                end = originalStart;
            }
        }
    }
}
