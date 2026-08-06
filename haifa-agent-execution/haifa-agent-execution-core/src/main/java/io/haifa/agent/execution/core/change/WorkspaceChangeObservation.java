package io.haifa.agent.execution.core.change;

import io.haifa.agent.project.changeset.FileChange;
import java.util.List;

/** One execution-scoped observation token. Completion returns the exact observed delta. */
public interface WorkspaceChangeObservation {
    List<FileChange> complete();

    default void cancel() {}
}
