package io.haifa.agent.git;

import io.haifa.agent.project.patch.PatchApplyResult;
import io.haifa.agent.project.patch.PatchConflict;
import io.haifa.agent.project.patch.PatchConflictCode;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.sandbox.api.GitWorktreeIsolationProvider;
import io.haifa.agent.sandbox.api.GitWorktreeRequest;
import io.haifa.agent.sandbox.api.IsolatedWorkspace;
import java.util.List;
import java.util.Objects;

public final class GitWorktreeCoordinator {
    private final GitWorktreeIsolationProvider isolation;
    private final GitRepositoryPort repositories;
    private final WorkspaceStore workspaces;
    private final WorktreePatchApplier patches;

    public GitWorktreeCoordinator(
            GitWorktreeIsolationProvider isolation,
            GitRepositoryPort repositories,
            WorkspaceStore workspaces,
            WorktreePatchApplier patches) {
        this.isolation = Objects.requireNonNull(isolation, "isolation must not be null");
        this.repositories = Objects.requireNonNull(repositories, "repositories must not be null");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.patches = Objects.requireNonNull(patches, "patches must not be null");
    }

    public RunWorkspaceBinding create(String childRunRef, GitWorktreeRequest request) {
        IsolatedWorkspace workspace = isolation.createWorktree(request);
        return new RunWorkspaceBinding(
                childRunRef,
                workspace.parentWorkspaceId(),
                workspace.childWorkspaceId(),
                workspace.baseRevision(),
                request.baseCommit());
    }

    public PatchApplyResult merge(WorktreeMergeRequest request) {
        var parent =
                workspaces.find(request.parentRepository().root().workspaceId()).orElseThrow();
        if (!parent.revision().equals(request.expectedParentRevision())) {
            return conflict(request, "parent workspace revision drifted");
        }
        GitInspection inspection = repositories.inspect(request.context(), request.parentRepository());
        if (!inspection.repository() || !inspection.commit().equalsIgnoreCase(request.expectedBaseCommit())) {
            return conflict(request, "parent Git base commit drifted");
        }
        if (!request.patchRequest().workspaceId().equals(parent.id())) {
            return conflict(request, "merge patch targets another workspace");
        }
        return patches.apply(request.patchRequest());
    }

    public void release(RunWorkspaceBinding binding, boolean confirmedDiscard) {
        isolation.releaseWorktree(binding.childWorkspaceId(), confirmedDiscard);
    }

    private static PatchApplyResult conflict(WorktreeMergeRequest request, String detail) {
        var path = request.patchRequest().document().files().get(0).targetPath();
        return new PatchApplyResult(
                request.patchRequest().document().sha256(),
                List.of(),
                List.of(new PatchConflict(path, PatchConflictCode.REVISION_CONFLICT, -1, detail)),
                false);
    }
}
