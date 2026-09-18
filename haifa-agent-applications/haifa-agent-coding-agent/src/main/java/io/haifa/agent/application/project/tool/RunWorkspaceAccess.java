package io.haifa.agent.application.project.tool;

import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Objects;
import java.util.Set;

public record RunWorkspaceAccess(WorkspaceId workspaceId, Set<String> capabilities) {
    public RunWorkspaceAccess {
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
    }
}
