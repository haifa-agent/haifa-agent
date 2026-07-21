package io.haifa.agent.execution.core.manifest;

import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.path.ProjectPath;
import java.util.Objects;

public record WorkspaceManifestEntry(ProjectPath path, FileType type, long size, String contentHash) {
    public WorkspaceManifestEntry {
        path = Objects.requireNonNull(path, "path must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        if (size < 0) throw new IllegalArgumentException("size must not be negative");
        contentHash = Objects.requireNonNull(contentHash, "contentHash must not be null");
    }
}
