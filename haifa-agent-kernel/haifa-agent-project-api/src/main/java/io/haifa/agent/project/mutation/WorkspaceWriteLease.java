package io.haifa.agent.project.mutation;

import io.haifa.agent.project.workspace.WorkspaceId;

public interface WorkspaceWriteLease extends AutoCloseable {
    WorkspaceId workspaceId();

    String operationId();

    @Override
    void close();
}
