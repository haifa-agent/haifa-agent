package io.haifa.agent.project.changeset;

import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.List;
import java.util.Optional;

public interface FileChangeSetStore {
    void create(FileChangeSet changeSet);

    void save(FileChangeSet changeSet, long expectedVersion);

    Optional<FileChangeSet> find(FileChangeSetId id);

    Optional<FileChangeSet> findByOperation(WorkspaceId workspaceId, String operationId);

    List<FileChangeSet> findByWorkspace(WorkspaceId workspaceId);
}
