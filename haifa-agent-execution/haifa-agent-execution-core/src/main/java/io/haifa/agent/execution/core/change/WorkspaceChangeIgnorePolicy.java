package io.haifa.agent.execution.core.change;

import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.path.ProjectPath;

/** Product-selected policy for excluding generated workspace content from change observation. */
@FunctionalInterface
public interface WorkspaceChangeIgnorePolicy {
    boolean ignores(ProjectPath path, FileType type);

    static WorkspaceChangeIgnorePolicy none() {
        return (path, type) -> false;
    }
}
