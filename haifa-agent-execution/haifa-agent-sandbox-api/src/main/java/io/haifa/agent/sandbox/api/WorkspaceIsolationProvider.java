package io.haifa.agent.sandbox.api;

import io.haifa.agent.project.workspace.WorkspaceId;

public interface WorkspaceIsolationProvider {
    IsolatedWorkspace createEphemeralCopy(EphemeralCopyRequest request);

    void release(WorkspaceId childWorkspaceId);
}
