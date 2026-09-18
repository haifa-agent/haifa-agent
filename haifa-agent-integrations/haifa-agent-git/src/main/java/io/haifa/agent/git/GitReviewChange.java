package io.haifa.agent.git;

import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.path.ProjectPath;
import java.util.Objects;

/** One bounded path summary reported by Git; no diff body is retained. */
public record GitReviewChange(FileChangeType type, ProjectPath path, ProjectPath destination, boolean binary) {
    public GitReviewChange {
        type = Objects.requireNonNull(type, "type must not be null");
        path = Objects.requireNonNull(path, "path must not be null");
        if (type == FileChangeType.MOVE && destination == null) {
            throw new IllegalArgumentException("destination is required for a move");
        }
    }
}
