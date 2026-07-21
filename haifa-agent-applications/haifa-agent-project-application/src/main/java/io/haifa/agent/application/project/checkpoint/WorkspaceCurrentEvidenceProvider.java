package io.haifa.agent.application.project.checkpoint;

import io.haifa.agent.project.snapshot.WorkspaceSnapshotEvidence;
import io.haifa.agent.project.snapshot.WorkspaceSnapshotStrategy;
import io.haifa.agent.project.workspace.Workspace;

@FunctionalInterface
public interface WorkspaceCurrentEvidenceProvider {
    WorkspaceSnapshotEvidence inspect(Workspace workspace, WorkspaceSnapshotStrategy strategy);
}
