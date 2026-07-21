package io.haifa.agent.git;

public interface GitRepositoryPort {
    GitInspection inspect(GitCommandContext context, GitRepositoryRef repository);

    GitStatus status(GitCommandContext context, GitRepositoryRef repository, int maxFiles);

    GitDiff diff(GitCommandContext context, GitRepositoryRef repository, int maxBytes);
}
