package io.haifa.agent.project.hostworkspace;

/** Bounded result of validating one candidate directory through the configured Git adapter. */
public enum HostGitInspectionStatus {
    WORKTREE_ROOT,
    NOT_WORKTREE_ROOT,
    UNAVAILABLE
}
