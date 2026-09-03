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
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.project.path.WorkspacePath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** One bounded DIRECT-command path shared by all internal Git read adapters. */
final class ExecutionBrokerGitReadClient {
    private final ExecutionBroker broker;
    private final IdentifierGenerator identifiers;
    private final SandboxProfileRef profile;
    private final String gitExecutable;
    private final AtomicLong sequence = new AtomicLong();

    ExecutionBrokerGitReadClient(
            ExecutionBroker broker, IdentifierGenerator identifiers, SandboxProfileRef profile, String gitExecutable) {
        this.broker = Objects.requireNonNull(broker, "broker must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.gitExecutable = Objects.requireNonNull(gitExecutable, "gitExecutable must not be null");
    }

    ExecutionResult run(
            GitCommandContext context, WorkspacePath workingDirectory, List<String> arguments, int outputBudget) {
        ArrayList<String> argv = new ArrayList<>();
        argv.add(gitExecutable);
        argv.add("-c");
        argv.add("credential.interactive=never");
        argv.addAll(arguments);
        String id = identifiers.nextValue();
        String idempotencyKey = "git-read:" + id + ":" + sequence.incrementAndGet();
        ExecutionRequest planned = new ExecutionRequest(
                new ExecutionId(id),
                idempotencyKey,
                context.executionContext(),
                workingDirectory.workspaceId(),
                workingDirectory,
                new ExecutionCommand(ExecutionCommandMode.DIRECT, argv),
                ExecutionEnvironmentRef.empty(),
                new ExecutionLimits(Duration.ofSeconds(15), outputBudget, 64 * 1024, 4),
                profile);
        String policyDecisionRef = Objects.requireNonNull(
                        context.authorizer().authorize(planned), "Git authorizer must not return null")
                .trim();
        if (policyDecisionRef.isEmpty()) {
            throw new IllegalStateException("Git authorizer returned a blank policy decision");
        }
        TrustedExecutionContext base = planned.context();
        TrustedExecutionContext authorized = new TrustedExecutionContext(
                base.tenant(), base.runRef(), base.actor(), base.frozenCapabilities(), policyDecisionRef);
        ExecutionRequest request = new ExecutionRequest(
                planned.id(),
                planned.idempotencyKey(),
                authorized,
                planned.workspaceId(),
                planned.workingDirectory(),
                planned.command(),
                planned.environmentRef(),
                planned.limits(),
                planned.sandboxProfileRef(),
                planned.input(),
                planned.invocationDigest(),
                planned.scratchSpace());
        return broker.execute(request);
    }
}
