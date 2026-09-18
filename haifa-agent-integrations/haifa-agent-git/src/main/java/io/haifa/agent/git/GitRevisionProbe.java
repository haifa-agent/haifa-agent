package io.haifa.agent.git;

public interface GitRevisionProbe {
    GitRevision inspectHead(GitCommandContext context, GitRepositoryRef repository);
}
