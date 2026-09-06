package io.haifa.agent.project.hostworkspace.registry;

import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.List;
import java.util.Optional;

/** Durable host-adapter port for CA workspace registrations. */
public interface HostWorkspaceRegistryStore {
    HostWorkspaceRegistryEntry create(HostWorkspaceRegistryEntry entry);

    Optional<HostWorkspaceRegistryEntry> find(ProjectId projectId, WorkspaceId workspaceRef);

    List<HostWorkspaceRegistryEntry> list(ProjectId projectId);

    HostWorkspaceRegistryEntry update(HostWorkspaceRegistryEntry entry, long expectedVersion);
}
