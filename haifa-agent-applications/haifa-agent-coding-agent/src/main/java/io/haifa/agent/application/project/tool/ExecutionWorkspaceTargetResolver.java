package io.haifa.agent.application.project.tool;

import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Objects;

/** Resolves the Coding Agent execution protocol into one authorized logical workspace path. */
@FunctionalInterface
public interface ExecutionWorkspaceTargetResolver {
    WorkspacePath resolve(RunWorkspaceAccess access, String workspaceRef, String relativeWorkdir);

    static ExecutionWorkspaceTargetResolver currentWorkspaceOnly() {
        return (access, workspaceRef, relativeWorkdir) -> {
            Objects.requireNonNull(access, "access must not be null");
            String exactWorkspaceRef = requireText(workspaceRef, "workspaceRef");
            if (!exactWorkspaceRef.equals(workspaceRef)) {
                throw new IllegalArgumentException("workspaceRef must use its canonical form");
            }
            WorkspaceId requested = new WorkspaceId(exactWorkspaceRef);
            if (!requested.equals(access.workspaceId())) {
                throw new IllegalArgumentException("workspaceRef is not available to this execution context");
            }
            String exactWorkdir = requireText(relativeWorkdir, "relativeWorkdir");
            if (!exactWorkdir.equals(relativeWorkdir)) {
                throw new IllegalArgumentException("relativeWorkdir must use its canonical form");
            }
            ProjectPath path = ".".equals(exactWorkdir) ? ProjectPath.root() : ProjectPath.of(exactWorkdir);
            if (!path.toString().equals(exactWorkdir)) {
                throw new IllegalArgumentException("relativeWorkdir must use its canonical form");
            }
            return new WorkspacePath(requested, path);
        };
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
