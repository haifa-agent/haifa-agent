package io.haifa.agent.git;

import io.haifa.agent.project.path.ProjectPath;
import java.util.Objects;

public record GitStatusEntry(String indexStatus, String worktreeStatus, ProjectPath path, ProjectPath originalPath) {
    public GitStatusEntry {
        indexStatus = Objects.requireNonNull(indexStatus, "indexStatus must not be null");
        worktreeStatus = Objects.requireNonNull(worktreeStatus, "worktreeStatus must not be null");
        path = Objects.requireNonNull(path, "path must not be null");
    }
}
