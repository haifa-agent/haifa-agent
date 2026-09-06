package io.haifa.agent.project.ledger;

import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.List;
import java.util.Map;

/**
 * Session-scoped ledger of managed file changes. Changes are grouped by the logical
 * {@link WorkspaceId} of the authorized directory they happened in, so two directories that contain
 * the same relative file name never collapse into one record.
 */
public interface SessionChangeLedger {

    void record(SessionFileChangeRecord change);

    List<SessionFileChangeRecord> rawChanges(WorkspaceId workspaceId);

    List<SessionFileChangeRecord> compactedChanges(WorkspaceId workspaceId);

    Map<WorkspaceId, List<SessionFileChangeRecord>> allCompactedChanges();

    void clear();
}
