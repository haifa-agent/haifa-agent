package io.haifa.agent.execution.core.tool;

import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.List;

/** Product-provided physical validation for logical files consumed by a fixed script Tool. */
@FunctionalInterface
public interface TrustedWorkspacePathValidator {
    void validate(WorkspaceId workspaceId, List<ProjectPath> inputPaths);

    static TrustedWorkspacePathValidator rejectWorkspaceInputs() {
        return (workspaceId, inputPaths) -> {
            if (!inputPaths.isEmpty()) {
                throw new SecurityException("trusted script Workspace input validation is not configured");
            }
        };
    }
}
