package io.haifa.agent.project.snapshot;

import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryWorkspaceSnapshotStore implements WorkspaceSnapshotStore {
    private final ConcurrentHashMap<WorkspaceSnapshotId, WorkspaceSnapshot> values = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WorkspaceSnapshotId> idempotency = new ConcurrentHashMap<>();

    @Override
    public synchronized void create(String key, WorkspaceSnapshot snapshot) {
        WorkspaceSnapshotId existing = idempotency.get(key);
        if (existing != null && !existing.equals(snapshot.id()))
            throw new IllegalStateException("snapshot idempotency conflict");
        if (values.putIfAbsent(snapshot.id(), snapshot) != null)
            throw new IllegalStateException("snapshot already exists");
        idempotency.put(key, snapshot.id());
    }

    @Override
    public Optional<WorkspaceSnapshot> find(WorkspaceSnapshotId id) {
        return Optional.ofNullable(values.get(id));
    }

    @Override
    public Optional<WorkspaceSnapshot> findByIdempotencyKey(String key) {
        WorkspaceSnapshotId id = idempotency.get(key);
        return id == null ? Optional.empty() : find(id);
    }

    @Override
    public List<WorkspaceSnapshot> findByWorkspace(WorkspaceId workspaceId) {
        return values.values().stream()
                .filter(value -> value.workspaceId().equals(workspaceId))
                .sorted(Comparator.comparing(WorkspaceSnapshot::createdAt)
                        .thenComparing(value -> value.id().value()))
                .toList();
    }
}
