package io.haifa.agent.project.ledger;

import io.haifa.agent.project.root.WorkspaceRootAlias;
import java.util.List;
import java.util.Map;

public interface SessionChangeLedger {

    void record(SessionFileChangeRecord change);

    List<SessionFileChangeRecord> rawChanges(WorkspaceRootAlias rootAlias);

    List<SessionFileChangeRecord> compactedChanges(WorkspaceRootAlias rootAlias);

    Map<WorkspaceRootAlias, List<SessionFileChangeRecord>> allCompactedChanges();

    void clear();
}
