package io.haifa.agent.execution.core.change;

import io.haifa.agent.project.workspace.WorkspaceId;

/** Begins a bounded observation window around an operating-system execution. */
@FunctionalInterface
public interface WorkspaceChangeObserver {
    WorkspaceChangeObservation begin(WorkspaceId workspaceId);
}
