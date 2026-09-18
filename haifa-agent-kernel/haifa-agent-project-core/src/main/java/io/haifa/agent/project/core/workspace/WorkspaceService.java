package io.haifa.agent.project.core.workspace;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.domain.ProjectStatus;
import io.haifa.agent.project.store.ProjectStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Provisions the logical identity of one explicitly authorized local directory. */
public final class WorkspaceService {
    private final ProjectStore projects;
    private final WorkspaceStore workspaces;
    private final TimeProvider time;

    public WorkspaceService(ProjectStore projects, WorkspaceStore workspaces, TimeProvider time) {
        this.projects = Objects.requireNonNull(projects, "projects must not be null");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    /**
     * Provisions or recovers the logical workspace of one explicitly authorized local directory. The
     * caller supplies a stable workspace id so re-authorizing the same directory recovers the same
     * logical identity instead of allocating a second one.
     */
    public Workspace provisionDirectory(ProjectId projectId, WorkspaceId workspaceId, String revisionDigest) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(revisionDigest, "revisionDigest must not be null");
        Project project = projects.find(projectId)
                .orElseThrow(() -> new IllegalArgumentException("project not found: " + projectId.value()));
        if (project.status() != ProjectStatus.ACTIVE) throw new IllegalStateException("project is not active");
        Optional<Workspace> existing = workspaces.find(workspaceId);
        if (existing.isPresent()) {
            Workspace workspace = existing.get();
            if (!workspace.projectId().equals(projectId)) {
                throw new IllegalStateException("workspace belongs to a different project");
            }
            return workspace;
        }
        Instant at = time.now();
        Workspace workspace = Workspace.provision(workspaceId, projectId, WorkspaceRevision.initial(revisionDigest), at)
                .activate(at);
        workspaces.create(workspace);
        return workspace;
    }
}
