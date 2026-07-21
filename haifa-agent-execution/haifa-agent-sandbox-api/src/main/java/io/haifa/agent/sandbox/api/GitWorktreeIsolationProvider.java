package io.haifa.agent.sandbox.api;

import io.haifa.agent.project.workspace.WorkspaceId;

public interface GitWorktreeIsolationProvider {
    IsolatedWorkspace createWorktree(GitWorktreeRequest request);

    void releaseWorktree(WorkspaceId childWorkspaceId, boolean confirmedDiscard);
}
