package io.haifa.agent.project.provider.local.scope;

/**
 * Canonical failure codes surfaced to local file tools for path input, authorization and permission
 * failures. They intentionally mirror the product error vocabulary instead of leaking internal
 * resolver details.
 */
public enum LocalScopeErrorCode {
    INVALID_ARGUMENT,
    ACCESS_DENIED,
    PERMISSION_DENIED,
    PATH_ESCAPE_DENIED,
    CROSS_DIRECTORY_MOVE
}
