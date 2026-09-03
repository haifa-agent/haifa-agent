package io.haifa.agent.git;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.SandboxProfileRef;
import java.util.List;

public final class ExecutionBrokerGitRevisionProbe implements GitRevisionProbe {
    private final ExecutionBrokerGitReadClient git;

    public ExecutionBrokerGitRevisionProbe(
            ExecutionBroker broker, IdentifierGenerator identifiers, SandboxProfileRef profile, String gitExecutable) {
        this.git = new ExecutionBrokerGitReadClient(broker, identifiers, profile, gitExecutable);
    }

    @Override
    public GitRevision inspectHead(GitCommandContext context, GitRepositoryRef repository) {
        ExecutionResult inside = run(context, repository, List.of("rev-parse", "--is-inside-work-tree"), 4096);
        if (inside.status() != ExecutionStatus.SUCCEEDED
                || !inside.stdout().summary().trim().equals("true")) {
            return new GitRevision(false, "", "", false, false);
        }
        String commit = run(context, repository, List.of("rev-parse", "HEAD"), 4096)
                .stdout()
                .summary()
                .trim();
        ExecutionResult branchResult = run(context, repository, List.of("symbolic-ref", "--short", "-q", "HEAD"), 4096);
        String branch = branchResult.status() == ExecutionStatus.SUCCEEDED
                ? branchResult.stdout().summary().trim()
                : "";
        ExecutionResult modules = run(context, repository, List.of("submodule", "status"), 4096);
        boolean hasSubmodules = modules.status() == ExecutionStatus.SUCCEEDED
                && !modules.stdout().summary().isBlank();
        return new GitRevision(true, commit, branch, branch.isEmpty(), hasSubmodules);
    }

    private ExecutionResult run(
            GitCommandContext context, GitRepositoryRef repository, List<String> arguments, int outputBudget) {
        return git.run(context, repository.root(), arguments, outputBudget);
    }
}
