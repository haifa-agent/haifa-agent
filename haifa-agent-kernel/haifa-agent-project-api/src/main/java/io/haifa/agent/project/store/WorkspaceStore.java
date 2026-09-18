package io.haifa.agent.project.store;

import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.List;
import java.util.Optional;

public interface WorkspaceStore {
    void create(Workspace workspace);

    void save(Workspace workspace, long expectedVersion);

    Optional<Workspace> find(WorkspaceId id);

    List<Workspace> findByProject(ProjectId projectId);
}
