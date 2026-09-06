package io.haifa.agent.project.filesystem;

import io.haifa.agent.project.path.WorkspacePath;
import java.util.Objects;

public record SearchResult(WorkspacePath path, int line, int column, String excerpt)
        implements Comparable<SearchResult> {
    public SearchResult {
        path = Objects.requireNonNull(path, "path must not be null");
        if (line < 1 || column < 1) throw new IllegalArgumentException("line and column must be positive");
        excerpt = Objects.requireNonNull(excerpt, "excerpt must not be null");
        if (excerpt.length() > 512) throw new IllegalArgumentException("excerpt exceeds 512 characters");
    }

    @Override
    public int compareTo(SearchResult other) {
        int pathOrder = path.compareTo(other.path);
        if (pathOrder != 0) return pathOrder;
        int lineOrder = Integer.compare(line, other.line);
        return lineOrder != 0 ? lineOrder : Integer.compare(column, other.column);
    }
}
