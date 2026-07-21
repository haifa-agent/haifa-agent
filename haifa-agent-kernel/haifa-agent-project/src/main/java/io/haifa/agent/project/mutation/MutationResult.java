package io.haifa.agent.project.mutation;

import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.changeset.FileChangeSetId;
import io.haifa.agent.project.changeset.FileChangeSetStatus;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record MutationResult(
        FileChangeSetId changeSetId,
        FileChangeSetStatus status,
        WorkspaceRevision baseRevision,
        WorkspaceRevision resultRevision,
        List<FileChange> changes,
        boolean atomic,
        boolean replayed) {
    public MutationResult {
        changeSetId = Objects.requireNonNull(changeSetId, "changeSetId must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        baseRevision = Objects.requireNonNull(baseRevision, "baseRevision must not be null");
        changes = List.copyOf(Objects.requireNonNull(changes, "changes must not be null"));
    }

    public Optional<WorkspaceRevision> optionalResultRevision() {
        return Optional.ofNullable(resultRevision);
    }
}
