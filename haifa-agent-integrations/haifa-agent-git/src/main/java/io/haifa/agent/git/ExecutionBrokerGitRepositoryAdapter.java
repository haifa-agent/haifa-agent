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
import io.haifa.agent.project.path.ProjectPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class ExecutionBrokerGitRepositoryAdapter implements GitRepositoryPort {
    private final ExecutionBroker broker;
    private final IdentifierGenerator identifiers;
    private final SandboxProfileRef profile;
    private final String gitExecutable;
    private final AtomicLong sequence = new AtomicLong();

    public ExecutionBrokerGitRepositoryAdapter(
            ExecutionBroker broker, IdentifierGenerator identifiers, SandboxProfileRef profile, String gitExecutable) {
        this.broker = Objects.requireNonNull(broker, "broker must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.gitExecutable = Objects.requireNonNull(gitExecutable, "gitExecutable must not be null");
    }

    @Override
    public GitInspection inspect(GitCommandContext context, GitRepositoryRef repository) {
        ExecutionResult inside = run(context, repository, List.of("rev-parse", "--is-inside-work-tree"), 4096);
        if (inside.status() != ExecutionStatus.SUCCEEDED
                || !inside.stdout().summary().trim().equals("true")) {
            return new GitInspection(false, "", "", false, false);
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
        return new GitInspection(true, commit, branch, branch.isEmpty(), hasSubmodules);
    }

    @Override
    public GitStatus status(GitCommandContext context, GitRepositoryRef repository, int maxFiles) {
        if (maxFiles < 1 || maxFiles > 10_000) throw new IllegalArgumentException("maxFiles is out of range");
        ExecutionResult result =
                run(context, repository, List.of("status", "--porcelain=v1", "--untracked-files=all"), 256 * 1024);
        requireSuccess(result);
        List<GitStatusEntry> entries = new ArrayList<>();
        for (String line : result.stdout().summary().split("\\R")) {
            if (line.length() < 4 || entries.size() >= maxFiles) continue;
            String rawPath = line.substring(3);
            ProjectPath original = null;
            if (rawPath.contains(" -> ")) {
                String[] rename = rawPath.split(" -> ", 2);
                original = ProjectPath.of(unquote(rename[0]));
                rawPath = rename[1];
            }
            entries.add(new GitStatusEntry(
                    line.substring(0, 1), line.substring(1, 2), ProjectPath.of(unquote(rawPath)), original));
        }
        return new GitStatus(
                entries, entries.size() >= maxFiles || result.stdout().truncated());
    }

    @Override
    public GitDiff diff(GitCommandContext context, GitRepositoryRef repository, int maxBytes) {
        if (maxBytes < 1 || maxBytes > 4 * 1024 * 1024) throw new IllegalArgumentException("maxBytes is out of range");
        ExecutionResult result =
                run(context, repository, List.of("diff", "--no-ext-diff", "--no-color", "--unified=3", "--"), maxBytes);
        requireSuccess(result);
        byte[] bytes = result.stdout().summary().getBytes(StandardCharsets.UTF_8);
        boolean truncated = result.stdout().truncated()
                || bytes.length > maxBytes
                || result.stdout().optionalAssetRef().isPresent();
        String value = bytes.length <= maxBytes
                ? result.stdout().summary()
                : new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
        return new GitDiff(value, truncated, Math.min(bytes.length, maxBytes));
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

    private static void requireSuccess(ExecutionResult result) {
        if (result.status() != ExecutionStatus.SUCCEEDED)
            throw new IllegalStateException("read-only Git command failed");
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("\"") && trimmed.endsWith("\"")
                ? trimmed.substring(1, trimmed.length() - 1)
                : trimmed;
    }
}
