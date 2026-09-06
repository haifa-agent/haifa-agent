package io.haifa.agent.project.path;

import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Objects;

public record WorkspacePath(WorkspaceId workspaceId, ProjectPath projectPath) implements Comparable<WorkspacePath> {
    public WorkspacePath {
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        projectPath = Objects.requireNonNull(projectPath, "projectPath must not be null");
    }

    public static WorkspacePath root(WorkspaceId workspaceId) {
        return new WorkspacePath(workspaceId, ProjectPath.root());
    }

    public WorkspacePath resolve(String child) {
        return new WorkspacePath(workspaceId, projectPath.resolve(child));
    }

    @Override
    public int compareTo(WorkspacePath other) {
        int workspace = workspaceId.value().compareTo(other.workspaceId.value());
        return workspace != 0 ? workspace : projectPath.compareTo(other.projectPath);
    }

    @Override
    public String toString() {
        return workspaceId.value() + ":" + projectPath;
    }
}
