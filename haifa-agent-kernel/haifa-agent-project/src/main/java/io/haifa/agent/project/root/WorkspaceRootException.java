package io.haifa.agent.project.root;

import java.util.Objects;

/**
 * Thrown when a multi-root path resolution or permission check fails.
 */
public class WorkspaceRootException extends RuntimeException {
    private final WorkspaceRootErrorCode code;
    private final String rootAlias;
    private final String path;

    public WorkspaceRootException(WorkspaceRootErrorCode code, String message) {
        this(code, null, null, message);
    }

    public WorkspaceRootException(WorkspaceRootErrorCode code, String rootAlias, String path, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.rootAlias = rootAlias;
        this.path = path;
    }

    public WorkspaceRootErrorCode code() {
        return code;
    }

    public String rootAlias() {
        return rootAlias;
    }

    public String path() {
        return path;
    }
}
