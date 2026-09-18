package io.haifa.agent.project.index.file;

import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.time.Instant;
import java.util.Objects;

public record FileIndexEntry(
        ProjectId projectId,
        WorkspaceId workspaceId,
        ProjectPath path,
        FileType type,
        long size,
        Instant observedAt,
        String contentHash,
        String language,
        long generation,
        boolean truncated) {
    public FileIndexEntry {
        projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        path = Objects.requireNonNull(path, "path must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        if (size < 0 || generation < 1) throw new IllegalArgumentException("invalid file index values");
        observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
        contentHash = Objects.requireNonNull(contentHash, "contentHash must not be null");
        language = Objects.requireNonNull(language, "language must not be null");
    }
}
