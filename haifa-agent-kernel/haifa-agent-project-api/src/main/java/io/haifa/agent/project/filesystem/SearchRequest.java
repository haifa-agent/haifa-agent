package io.haifa.agent.project.filesystem;

import io.haifa.agent.project.path.WorkspacePath;
import java.util.Objects;

public record SearchRequest(
        WorkspacePath root,
        String query,
        int maxScannedFiles,
        int maxResults,
        long maxFileBytes,
        boolean caseSensitive) {
    public SearchRequest {
        root = Objects.requireNonNull(root, "root must not be null");
        query = Objects.requireNonNull(query, "query must not be null");
        if (query.isEmpty()) throw new IllegalArgumentException("query must not be empty");
        if (maxScannedFiles < 1 || maxResults < 1 || maxFileBytes < 1) {
            throw new IllegalArgumentException("search budgets must be positive");
        }
    }
}
