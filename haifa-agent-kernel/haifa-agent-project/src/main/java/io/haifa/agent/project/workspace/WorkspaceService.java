package io.haifa.agent.project.workspace;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.domain.ProjectStatus;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.store.ProjectStore;
import io.haifa.agent.project.store.WorkspaceBindingStore;
import io.haifa.agent.project.store.WorkspaceStore;
import java.util.Objects;

public final class WorkspaceService {
    private final ProjectStore projects;
    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;

    public WorkspaceService(
            ProjectStore projects,
            WorkspaceStore workspaces,
            WorkspaceBindingStore bindings,
            IdentifierGenerator identifiers,
            TimeProvider time) {
        this.projects = Objects.requireNonNull(projects, "projects must not be null");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    public Workspace provisionPrimary(ProjectId projectId, WorkspaceBinding binding, String semanticsId) {
        Project project = projects.find(projectId)
                .orElseThrow(() -> new IllegalArgumentException("project not found: " + projectId.value()));
        if (project.status() != ProjectStatus.ACTIVE) throw new IllegalStateException("project is not active");
        if (binding.status() != io.haifa.agent.project.binding.WorkspaceBindingStatus.ACTIVE) {
            throw new IllegalStateException("binding is not active");
        }
        if (bindings.find(binding.id()).isEmpty()) bindings.create(binding);
        WorkspaceId id = new WorkspaceId(identifiers.nextValue());
        Workspace workspace = Workspace.provision(
                        id,
                        projectId,
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), binding.id(), semanticsId),
                        WorkspaceRevision.initial(binding.rootFingerprint()),
                        time.now())
                .activate(time.now());
        workspaces.create(workspace);
        if (project.defaultWorkspace().isEmpty()) {
            Project updated = project.assignDefaultWorkspace(id, time.now());
            projects.save(updated, project.version());
        }
        return workspace;
    }
}
