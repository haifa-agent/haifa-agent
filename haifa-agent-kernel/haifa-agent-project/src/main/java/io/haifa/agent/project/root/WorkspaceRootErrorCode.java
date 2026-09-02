package io.haifa.agent.project.root;

/**
 * Structured error codes for multi-root workspace operations.
 */
public enum WorkspaceRootErrorCode {
    ROOT_ALIAS_NOT_FOUND,
    ROOT_READ_ONLY,
    ABSOLUTE_PATH_FORBIDDEN,
    PATH_ESCAPE_FORBIDDEN,
    INVALID_ROOT_ALIAS,
    DUPLICATE_ROOT_ALIAS,
    ROOT_PATH_NOT_FOUND
}
