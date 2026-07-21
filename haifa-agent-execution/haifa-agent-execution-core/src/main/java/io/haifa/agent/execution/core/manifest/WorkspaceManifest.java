package io.haifa.agent.execution.core.manifest;

import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import java.util.List;
import java.util.Objects;

public record WorkspaceManifest(
        WorkspaceId workspaceId,
        WorkspaceRevision revision,
        String ignorePolicyVersion,
        List<WorkspaceManifestEntry> entries) {
    public WorkspaceManifest {
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        revision = Objects.requireNonNull(revision, "revision must not be null");
        ignorePolicyVersion = Objects.requireNonNull(ignorePolicyVersion, "ignorePolicyVersion must not be null");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
    }
}
