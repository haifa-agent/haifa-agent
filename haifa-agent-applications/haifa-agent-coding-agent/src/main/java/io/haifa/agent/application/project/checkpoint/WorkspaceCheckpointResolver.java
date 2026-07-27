package io.haifa.agent.application.project.checkpoint;

import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointCaptureContext;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointRestoreContext;

public interface WorkspaceCheckpointResolver {
    WorkspaceCheckpointPlan capturePlan(CapabilityCheckpointCaptureContext context);

    WorkspaceCheckpointAccess currentAccess(CapabilityCheckpointRestoreContext context, WorkspaceCheckpointState state);
}
