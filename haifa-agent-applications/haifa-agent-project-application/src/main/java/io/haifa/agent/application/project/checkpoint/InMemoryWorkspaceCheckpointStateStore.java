package io.haifa.agent.application.project.checkpoint;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryWorkspaceCheckpointStateStore implements WorkspaceCheckpointStateStore {
    private final ConcurrentHashMap<String, WorkspaceCheckpointState> values = new ConcurrentHashMap<>();

    @Override
    public void create(String payloadRef, WorkspaceCheckpointState state) {
        if (values.putIfAbsent(payloadRef, state) != null) {
            throw new IllegalStateException("workspace checkpoint payload already exists");
        }
    }

    @Override
    public Optional<WorkspaceCheckpointState> find(String payloadRef) {
        return Optional.ofNullable(values.get(payloadRef));
    }
}
