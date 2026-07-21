package io.haifa.agent.git;

import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import java.util.Objects;

public record RunWorkspaceBinding(
        String runRef,
        WorkspaceId parentWorkspaceId,
        WorkspaceId childWorkspaceId,
        WorkspaceRevision parentBaseRevision,
        String baseCommit) {
    public RunWorkspaceBinding {
        runRef = Objects.requireNonNull(runRef, "runRef must not be null").trim();
        if (runRef.isEmpty()) throw new IllegalArgumentException("runRef must not be blank");
        parentWorkspaceId = Objects.requireNonNull(parentWorkspaceId, "parentWorkspaceId must not be null");
        childWorkspaceId = Objects.requireNonNull(childWorkspaceId, "childWorkspaceId must not be null");
        parentBaseRevision = Objects.requireNonNull(parentBaseRevision, "parentBaseRevision must not be null");
        baseCommit = Objects.requireNonNull(baseCommit, "baseCommit must not be null");
    }
}
