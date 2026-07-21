package io.haifa.agent.project.store;

import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectId;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryProjectStore implements ProjectStore {
    private final ConcurrentHashMap<ProjectId, Project> projects = new ConcurrentHashMap<>();

    @Override
    public void create(Project project) {
        if (projects.putIfAbsent(project.id(), project) != null) {
            throw new ProjectStoreConflictException(
                    "project already exists: " + project.id().value());
        }
    }

    @Override
    public void save(Project project, long expectedVersion) {
        projects.compute(project.id(), (id, current) -> {
            if (current == null) throw new ProjectStoreConflictException("project does not exist: " + id.value());
            if (current.version() != expectedVersion || project.version() != expectedVersion + 1) {
                throw new ProjectStoreConflictException("project version conflict: " + id.value());
            }
            return project;
        });
    }

    @Override
    public Optional<Project> find(ProjectId id) {
        return Optional.ofNullable(projects.get(id));
    }
}
