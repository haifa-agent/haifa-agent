package io.haifa.agent.project.snapshot;

import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.List;
import java.util.Optional;

public interface WorkspaceSnapshotStore {
    void create(String idempotencyKey, WorkspaceSnapshot snapshot);

    Optional<WorkspaceSnapshot> find(WorkspaceSnapshotId id);

    Optional<WorkspaceSnapshot> findByIdempotencyKey(String idempotencyKey);

    List<WorkspaceSnapshot> findByWorkspace(WorkspaceId workspaceId);
}
