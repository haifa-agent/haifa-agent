package io.haifa.agent.cli.remote;

import java.util.Objects;

/** Bounded Host-authored display metadata; no client filesystem resolution is permitted. */
public record RemoteCodingWorkspaceInfo(
        String workingDirectory, String gitBranch, String worktree, String worktreeBranch, String executionDirectory) {
    public RemoteCodingWorkspaceInfo {
        workingDirectory = checked(workingDirectory);
        gitBranch = checked(gitBranch);
        worktree = checked(worktree);
        worktreeBranch = checked(worktreeBranch);
        executionDirectory = checked(executionDirectory);
        if (workingDirectory.isBlank() || executionDirectory.isBlank()) {
            throw new IllegalArgumentException("Host workspace directories must not be blank");
        }
    }

    private static String checked(String value) {
        Objects.requireNonNull(value, "workspace display field must not be null");
        if (value.length() > 4096 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid Host workspace display field");
        }
        return value;
    }
}
