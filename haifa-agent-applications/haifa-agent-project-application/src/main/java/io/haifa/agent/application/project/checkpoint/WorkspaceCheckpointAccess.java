package io.haifa.agent.application.project.checkpoint;

import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.workspace.Workspace;

public record WorkspaceCheckpointAccess(Workspace workspace, WorkspaceBinding binding, boolean authorized) {}
