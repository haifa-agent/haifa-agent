package io.haifa.agent.project.changeset;

import io.haifa.agent.project.store.ProjectStoreConflictException;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryFileChangeSetStore implements FileChangeSetStore {
    private final ConcurrentHashMap<FileChangeSetId, FileChangeSet> values = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<OperationKey, FileChangeSetId> operations = new ConcurrentHashMap<>();

    @Override
    public synchronized void create(FileChangeSet changeSet) {
        OperationKey operation = new OperationKey(changeSet.workspaceId(), changeSet.operationId());
        if (operations.containsKey(operation) || values.containsKey(changeSet.id())) {
            throw new ProjectStoreConflictException("change set operation already exists");
        }
        values.put(changeSet.id(), changeSet);
        operations.put(operation, changeSet.id());
    }

    @Override
    public void save(FileChangeSet changeSet, long expectedVersion) {
        values.compute(changeSet.id(), (id, current) -> {
            if (current == null) throw new ProjectStoreConflictException("change set does not exist");
            if (current.version() != expectedVersion || changeSet.version() != expectedVersion + 1) {
                throw new ProjectStoreConflictException("change set version conflict");
            }
            return changeSet;
        });
    }

    @Override
    public Optional<FileChangeSet> find(FileChangeSetId id) {
        return Optional.ofNullable(values.get(id));
    }

    @Override
    public Optional<FileChangeSet> findByOperation(WorkspaceId workspaceId, String operationId) {
        return Optional.ofNullable(operations.get(new OperationKey(workspaceId, operationId)))
                .flatMap(this::find);
    }

    @Override
    public List<FileChangeSet> findByWorkspace(WorkspaceId workspaceId) {
        return values.values().stream()
                .filter(value -> value.workspaceId().equals(workspaceId))
                .sorted(Comparator.comparing(FileChangeSet::createdAt)
                        .thenComparing(value -> value.id().value()))
                .toList();
    }

    public Map<FileChangeSetId, FileChangeSet> snapshot() {
        return Map.copyOf(values);
    }

    private record OperationKey(WorkspaceId workspaceId, String operationId) {}
}
