package io.haifa.agent.project.filesystem;

import io.haifa.agent.project.path.WorkspacePath;
import java.util.Objects;
import java.util.Optional;

public final class WorkspaceFileException extends RuntimeException {
    private final WorkspaceFileErrorCode code;
    private final WorkspacePath logicalPath;

    public WorkspaceFileException(WorkspaceFileErrorCode code, WorkspacePath logicalPath, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.logicalPath = logicalPath;
    }

    public WorkspaceFileErrorCode code() {
        return code;
    }

    public Optional<WorkspacePath> logicalPath() {
        return Optional.ofNullable(logicalPath);
    }
}
