package io.haifa.agent.sandbox.api;

import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Objects;

public record WorkspaceMount(WorkspaceId workspaceId, boolean readOnly) {
    public WorkspaceMount {
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
    }
}
