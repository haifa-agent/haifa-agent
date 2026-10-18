package io.haifa.agent.application.coding.terminal.state;

import java.util.Objects;

/** Trusted local workspace facts supplied by the highest-layer application assembly. */
public record TerminalWorkspaceContext(
        String workingDirectory,
        String gitBranch,
        String worktree,
        String worktreeBranch) {
    public TerminalWorkspaceContext {
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory must not be null").strip();
        gitBranch = Objects.requireNonNull(gitBranch, "gitBranch must not be null").strip();
        worktree = Objects.requireNonNull(worktree, "worktree must not be null").strip();
        worktreeBranch = Objects.requireNonNull(worktreeBranch, "worktreeBranch must not be null").strip();
    }

    public TerminalWorkspaceContext(String workingDirectory, String gitBranch) {
        this(workingDirectory, gitBranch, "", "");
    }

    public static TerminalWorkspaceContext empty() {
        return new TerminalWorkspaceContext("", "", "", "");
    }
}
