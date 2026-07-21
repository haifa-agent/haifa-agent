package io.haifa.agent.project.store;

import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryWorkspaceStore implements WorkspaceStore {
    private final ConcurrentHashMap<WorkspaceId, Workspace> workspaces = new ConcurrentHashMap<>();

    @Override
    public void create(Workspace workspace) {
        if (workspaces.putIfAbsent(workspace.id(), workspace) != null) {
            throw new ProjectStoreConflictException(
                    "workspace already exists: " + workspace.id().value());
        }
    }

    @Override
    public void save(Workspace workspace, long expectedVersion) {
        workspaces.compute(workspace.id(), (id, current) -> {
            if (current == null) throw new ProjectStoreConflictException("workspace does not exist: " + id.value());
            if (current.version() != expectedVersion || workspace.version() != expectedVersion + 1) {
                throw new ProjectStoreConflictException("workspace version conflict: " + id.value());
            }
            return workspace;
        });
    }

    @Override
    public Optional<Workspace> find(WorkspaceId id) {
        return Optional.ofNullable(workspaces.get(id));
    }

    @Override
    public List<Workspace> findByProject(ProjectId projectId) {
        return workspaces.values().stream()
                .filter(workspace -> workspace.projectId().equals(projectId))
                .sorted(Comparator.comparing(workspace -> workspace.id().value()))
                .toList();
    }
}
