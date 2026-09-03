package io.haifa.agent.project.provider.local.scope;

import java.util.Objects;

/**
 * Thrown when a local file tool path input, scope authorization or directory permission check fails.
 */
public class LocalWorkspaceScopeException extends RuntimeException {
    private final LocalScopeErrorCode code;
    private final String path;

    public LocalWorkspaceScopeException(LocalScopeErrorCode code, String path, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.path = path;
    }

    public static LocalWorkspaceScopeException invalidArgument(String path, String message) {
        return new LocalWorkspaceScopeException(LocalScopeErrorCode.INVALID_ARGUMENT, path, message);
    }

    public static LocalWorkspaceScopeException accessDenied(String path, String message) {
        return new LocalWorkspaceScopeException(LocalScopeErrorCode.ACCESS_DENIED, path, message);
    }

    public static LocalWorkspaceScopeException permissionDenied(String path, String message) {
        return new LocalWorkspaceScopeException(LocalScopeErrorCode.PERMISSION_DENIED, path, message);
    }

    public static LocalWorkspaceScopeException pathEscapeDenied(String path, String message) {
        return new LocalWorkspaceScopeException(LocalScopeErrorCode.PATH_ESCAPE_DENIED, path, message);
    }

    public static LocalWorkspaceScopeException crossDirectoryMove(String path, String message) {
        return new LocalWorkspaceScopeException(LocalScopeErrorCode.CROSS_DIRECTORY_MOVE, path, message);
    }

    public LocalScopeErrorCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
