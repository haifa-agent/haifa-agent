package io.haifa.agent.execution.core.change;

import io.haifa.agent.execution.core.manifest.ManifestDiffService;
import io.haifa.agent.execution.core.manifest.WorkspaceManifest;
import io.haifa.agent.execution.core.manifest.WorkspaceManifestService;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Objects;

/** Compatibility observer for assemblies that have not yet selected an incremental provider. */
public final class ManifestWorkspaceChangeObserver implements WorkspaceChangeObserver {
    private final WorkspaceManifestService manifests;
    private final ManifestDiffService diff;

    public ManifestWorkspaceChangeObserver(WorkspaceManifestService manifests, ManifestDiffService diff) {
        this.manifests = Objects.requireNonNull(manifests, "manifests must not be null");
        this.diff = Objects.requireNonNull(diff, "diff must not be null");
    }

    @Override
    public WorkspaceChangeObservation begin(WorkspaceId workspaceId) {
        WorkspaceManifest before = manifests.capture(workspaceId);
        return () -> diff.diff(before, manifests.capture(workspaceId));
    }
}
