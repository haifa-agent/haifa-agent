package io.haifa.agent.project.store;

import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import java.util.Optional;

public interface WorkspaceBindingStore {
    void create(WorkspaceBinding binding);

    void save(WorkspaceBinding binding, long expectedVersion);

    Optional<WorkspaceBinding> find(WorkspaceBindingId id);
}
