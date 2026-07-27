package io.haifa.agent.application.project.checkpoint;

import io.haifa.agent.project.snapshot.WorkspaceSnapshot;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointRestoreContext;

public interface WorkspaceIsolatedRestorePort {
    boolean supports(WorkspaceCheckpointState state);

    void restoreToNewControlledWorkspace(
            CapabilityCheckpointRestoreContext context, WorkspaceCheckpointState state, WorkspaceSnapshot snapshot);

    static WorkspaceIsolatedRestorePort unavailable() {
        return new WorkspaceIsolatedRestorePort() {
            @Override
            public boolean supports(WorkspaceCheckpointState state) {
                return false;
            }

            @Override
            public void restoreToNewControlledWorkspace(
                    CapabilityCheckpointRestoreContext context,
                    WorkspaceCheckpointState state,
                    WorkspaceSnapshot snapshot) {
                throw new UnsupportedOperationException("isolated full-copy restore adapter is unavailable");
            }
        };
    }
}
