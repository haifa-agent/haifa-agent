package io.haifa.agent.project.mutation;

import io.haifa.agent.project.path.WorkspacePath;
import java.util.Objects;

public final class WorkspaceMutationException extends RuntimeException {
    private final MutationErrorCode code;
    private final WorkspacePath path;

    public WorkspaceMutationException(MutationErrorCode code, WorkspacePath path, String safeMessage) {
        super(Objects.requireNonNull(safeMessage, "safeMessage must not be null"));
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.path = Objects.requireNonNull(path, "path must not be null");
    }

    public MutationErrorCode code() {
        return code;
    }

    public WorkspacePath path() {
        return path;
    }
}
