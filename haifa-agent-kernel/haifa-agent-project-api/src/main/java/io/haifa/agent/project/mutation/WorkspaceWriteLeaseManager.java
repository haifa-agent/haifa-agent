package io.haifa.agent.project.mutation;

import io.haifa.agent.project.workspace.WorkspaceId;

public interface WorkspaceWriteLeaseManager {
    WorkspaceWriteLease acquire(WorkspaceId workspaceId, String operationId);
}
