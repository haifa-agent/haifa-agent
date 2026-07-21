package io.haifa.agent.project.snapshot;

import io.haifa.agent.project.workspace.Workspace;

@FunctionalInterface
public interface WorkspaceSnapshotEvidenceProvider {
    WorkspaceSnapshotEvidence capture(Workspace workspace, WorkspaceSnapshotStrategy strategy);
}
