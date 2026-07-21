package io.haifa.agent.project.quarantine;

import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.List;
import java.util.Optional;

public interface QuarantineStore {
    void create(QuarantineEntry entry);

    Optional<QuarantineEntry> find(String token);

    Optional<QuarantineEntry> findByOperation(WorkspaceId workspaceId, String operationId);

    void remove(String token);

    List<QuarantineEntry> findByWorkspace(WorkspaceId workspaceId);
}
