package io.haifa.agent.project.hostworkspace.scope;

import java.util.Objects;

/**
 * Thrown when a local file tool path input, scope authorization or directory permission check fails.
 */
public class HostWorkspaceScopeException extends RuntimeException {
    private final HostWorkspaceScopeErrorCode code;
    private final String path;

    public HostWorkspaceScopeException(HostWorkspaceScopeErrorCode code, String path, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.path = path;
    }

    public static HostWorkspaceScopeException invalidArgument(String path, String message) {
        return new HostWorkspaceScopeException(HostWorkspaceScopeErrorCode.INVALID_ARGUMENT, path, message);
    }

    public static HostWorkspaceScopeException accessDenied(String path, String message) {
        return new HostWorkspaceScopeException(HostWorkspaceScopeErrorCode.ACCESS_DENIED, path, message);
    }

    public static HostWorkspaceScopeException permissionDenied(String path, String message) {
        return new HostWorkspaceScopeException(HostWorkspaceScopeErrorCode.PERMISSION_DENIED, path, message);
    }

    public static HostWorkspaceScopeException pathEscapeDenied(String path, String message) {
        return new HostWorkspaceScopeException(HostWorkspaceScopeErrorCode.PATH_ESCAPE_DENIED, path, message);
    }

    public static HostWorkspaceScopeException crossDirectoryMove(String path, String message) {
        return new HostWorkspaceScopeException(HostWorkspaceScopeErrorCode.CROSS_DIRECTORY_MOVE, path, message);
    }

    public HostWorkspaceScopeErrorCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
