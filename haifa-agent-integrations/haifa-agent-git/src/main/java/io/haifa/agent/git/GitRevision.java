package io.haifa.agent.git;

public record GitRevision(boolean repository, String commit, String branch, boolean detached, boolean hasSubmodules) {
    public GitRevision {
        commit = commit == null ? "" : commit.trim();
        branch = branch == null ? "" : branch.trim();
        if (repository && commit.isEmpty()) throw new IllegalArgumentException("repository inspection requires commit");
    }
}
