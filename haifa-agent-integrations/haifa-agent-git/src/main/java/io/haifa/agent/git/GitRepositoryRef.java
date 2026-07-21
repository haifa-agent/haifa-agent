package io.haifa.agent.git;

import io.haifa.agent.project.path.WorkspacePath;
import java.util.Objects;

public record GitRepositoryRef(WorkspacePath root) {
    public GitRepositoryRef {
        root = Objects.requireNonNull(root, "root must not be null");
    }
}
