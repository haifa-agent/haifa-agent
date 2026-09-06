package io.haifa.agent.project.index.symbol;

import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Objects;

public record SymbolRecord(
        WorkspaceId workspaceId,
        String name,
        String qualifiedName,
        SymbolKind kind,
        ProjectPath path,
        int startLine,
        int startColumn,
        String signature,
        String visibility,
        String contentHash,
        long generation) {
    public SymbolRecord {
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        name = require(name, "name");
        qualifiedName = require(qualifiedName, "qualifiedName");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        path = Objects.requireNonNull(path, "path must not be null");
        if (startLine < 1 || startColumn < 1 || generation < 1) throw new IllegalArgumentException("invalid location");
        signature = require(signature, "signature");
        visibility = require(visibility, "visibility");
        contentHash = require(contentHash, "contentHash");
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
