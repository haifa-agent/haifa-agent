package io.haifa.agent.project.index.document;

import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Objects;

public record DocumentNode(
        WorkspaceId workspaceId,
        String nodeId,
        DocumentNodeKind kind,
        String title,
        int level,
        ProjectPath path,
        int startLine,
        int endLine,
        String summary,
        String contentHash,
        long generation) {
    public DocumentNode {
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        nodeId = require(nodeId, "nodeId");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        title = require(title, "title");
        if (level < 0 || level > 6 || startLine < 1 || endLine < startLine || generation < 1) {
            throw new IllegalArgumentException("invalid document location");
        }
        path = Objects.requireNonNull(path, "path must not be null");
        summary = Objects.requireNonNull(summary, "summary must not be null");
        contentHash = require(contentHash, "contentHash");
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
