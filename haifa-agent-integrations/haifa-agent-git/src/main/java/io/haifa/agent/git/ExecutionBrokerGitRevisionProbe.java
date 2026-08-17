package io.haifa.agent.git;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.SandboxProfileRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class ExecutionBrokerGitRevisionProbe implements GitRevisionProbe {
    private final ExecutionBroker broker;
    private final IdentifierGenerator identifiers;
    private final SandboxProfileRef profile;
    private final String gitExecutable;
    private final AtomicLong sequence = new AtomicLong();

    public ExecutionBrokerGitRevisionProbe(
            ExecutionBroker broker, IdentifierGenerator identifiers, SandboxProfileRef profile, String gitExecutable) {
        this.broker = Objects.requireNonNull(broker, "broker must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.gitExecutable = Objects.requireNonNull(gitExecutable, "gitExecutable must not be null");
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
        ArrayList<String> argv = new ArrayList<>();
        argv.add(gitExecutable);
        argv.add("-c");
        argv.add("credential.interactive=never");
        argv.addAll(arguments);
        String id = identifiers.nextValue();
        return broker.execute(new ExecutionRequest(
                new ExecutionId(id),
                "git-read:" + id + ":" + sequence.incrementAndGet(),
                context.executionContext(),
                repository.root().workspaceId(),
                repository.root(),
                new ExecutionCommand(ExecutionCommandMode.DIRECT, argv),
                ExecutionEnvironmentRef.empty(),
                new ExecutionLimits(Duration.ofSeconds(15), outputBudget, 64 * 1024, 4),
                profile));
    }
}
