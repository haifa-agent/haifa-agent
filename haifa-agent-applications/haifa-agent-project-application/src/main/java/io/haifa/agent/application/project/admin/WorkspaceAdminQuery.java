package io.haifa.agent.application.project.admin;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.time.Instant;
import java.util.Objects;

public final class WorkspaceAdminQuery {
    private final ProjectAdminQuery projects;

    public WorkspaceAdminQuery(ProjectAdminQuery projects) {
        this.projects = Objects.requireNonNull(projects);
    }

    public AdminPage<WorkspaceSnapshotView> snapshots(
            PrincipalRef actor,
            ProjectId projectId,
            WorkspaceId workspaceId,
            Instant from,
            Instant to,
            String status,
            int offset,
            int limit) {
        return projects.snapshots(actor, projectId, workspaceId, from, to, status, offset, limit);
    }

    public AdminPage<FileChangeSetView> changeSets(
            PrincipalRef actor, ProjectId projectId, WorkspaceId workspaceId, int offset, int limit) {
        return projects.changeSets(actor, projectId, workspaceId, offset, limit);
    }
}
