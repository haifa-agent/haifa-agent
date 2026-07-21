package io.haifa.agent.project.binding;

import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.store.ProjectStore;
import io.haifa.agent.project.store.WorkspaceBindingStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceStatus;
import java.util.Objects;

public final class WorkspaceBindingResolver {
    private final ProjectStore projects;
    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;

    public WorkspaceBindingResolver(ProjectStore projects, WorkspaceStore workspaces, WorkspaceBindingStore bindings) {
        this.projects = Objects.requireNonNull(projects, "projects must not be null");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
    }

    public ResolvedWorkspaceBinding resolvePrimary(ProjectId projectId) {
        var project = projects.find(projectId)
                .orElseThrow(() -> new IllegalArgumentException("project not found: " + projectId.value()));
        var workspaceId = project.defaultWorkspace()
                .orElseThrow(() -> new IllegalStateException("project has no default workspace"));
        Workspace workspace = workspaces
                .find(workspaceId)
                .filter(value -> value.status() == WorkspaceStatus.ACTIVE)
                .orElseThrow(() -> new IllegalStateException("default workspace is not active"));
        WorkspaceBinding binding = bindings.find(workspace.root().bindingId())
                .filter(value -> value.status() == WorkspaceBindingStatus.ACTIVE)
                .orElseThrow(() -> new IllegalStateException("workspace binding is not active"));
        return new ResolvedWorkspaceBinding(workspace, binding);
    }

    public record ResolvedWorkspaceBinding(Workspace workspace, WorkspaceBinding binding) {
        public ResolvedWorkspaceBinding {
            workspace = Objects.requireNonNull(workspace, "workspace must not be null");
            binding = Objects.requireNonNull(binding, "binding must not be null");
        }
    }
}
