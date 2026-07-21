package io.haifa.agent.project.store;

import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectId;
import java.util.Optional;

public interface ProjectStore {
    void create(Project project);

    void save(Project project, long expectedVersion);

    Optional<Project> find(ProjectId id);
}
