package io.haifa.agent.project.hostworkspace.registry;

/** Trusted source that caused a local Coding workspace root to enter the registry. */
public enum HostWorkspaceRegistrySource {
    INITIAL,
    APPROVED_ATTACH,
    APPROVED_WORKTREE_CREATE
}
