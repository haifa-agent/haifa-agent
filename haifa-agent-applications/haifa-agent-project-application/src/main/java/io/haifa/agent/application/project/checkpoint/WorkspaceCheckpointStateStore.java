package io.haifa.agent.application.project.checkpoint;

import java.util.Optional;

public interface WorkspaceCheckpointStateStore {
    void create(String payloadRef, WorkspaceCheckpointState state);

    Optional<WorkspaceCheckpointState> find(String payloadRef);
}
