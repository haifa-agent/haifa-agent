package io.haifa.agent.project.filesystem;

import io.haifa.agent.project.path.WorkspacePath;
import java.util.Objects;

public record FileListRequest(WorkspacePath directory, int offset, int limit) {
    public FileListRequest {
        directory = Objects.requireNonNull(directory, "directory must not be null");
        if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("limit must be between 1 and 10000");
    }
}
