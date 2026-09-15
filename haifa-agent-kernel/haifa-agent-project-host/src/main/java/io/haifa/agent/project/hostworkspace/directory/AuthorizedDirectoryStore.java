package io.haifa.agent.project.hostworkspace.directory;

import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.List;
import java.util.Optional;

/** Durable host-adapter port for CA workspace registrations. */
public interface AuthorizedDirectoryStore {
    AuthorizedDirectoryEntry create(AuthorizedDirectoryEntry entry);

    Optional<AuthorizedDirectoryEntry> find(ProjectId projectId, WorkspaceId workspaceRef);

    List<AuthorizedDirectoryEntry> list(ProjectId projectId);

    AuthorizedDirectoryEntry update(AuthorizedDirectoryEntry entry, long expectedVersion);
}
