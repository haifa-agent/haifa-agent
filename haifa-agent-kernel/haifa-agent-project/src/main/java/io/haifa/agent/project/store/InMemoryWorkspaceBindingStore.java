package io.haifa.agent.project.store;

import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryWorkspaceBindingStore implements WorkspaceBindingStore {
    private final ConcurrentHashMap<WorkspaceBindingId, WorkspaceBinding> bindings = new ConcurrentHashMap<>();

    @Override
    public void create(WorkspaceBinding binding) {
        if (bindings.putIfAbsent(binding.id(), binding) != null) {
            throw new ProjectStoreConflictException(
                    "binding already exists: " + binding.id().value());
        }
    }

    @Override
    public void save(WorkspaceBinding binding, long expectedVersion) {
        bindings.compute(binding.id(), (id, current) -> {
            if (current == null) throw new ProjectStoreConflictException("binding does not exist: " + id.value());
            if (current.version() != expectedVersion || binding.version() != expectedVersion + 1) {
                throw new ProjectStoreConflictException("binding version conflict: " + id.value());
            }
            return binding;
        });
    }

    @Override
    public Optional<WorkspaceBinding> find(WorkspaceBindingId id) {
        return Optional.ofNullable(bindings.get(id));
    }
}
