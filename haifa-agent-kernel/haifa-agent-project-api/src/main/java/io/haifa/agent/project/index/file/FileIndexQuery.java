package io.haifa.agent.project.index.file;

import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Objects;

public record FileIndexQuery(WorkspaceId workspaceId, ProjectPath prefix, String text, int offset, int limit) {
    public FileIndexQuery {
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        prefix = Objects.requireNonNull(prefix, "prefix must not be null");
        text = Objects.requireNonNull(text, "text must not be null").trim().toLowerCase(java.util.Locale.ROOT);
        if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("invalid query window");
    }
}
