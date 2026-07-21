package io.haifa.agent.runtime.core.completion;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import io.haifa.agent.runtime.core.delegation.DelegationPort;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.tool.ToolPipeline;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DefaultCompletionGuard implements CompletionGuard {
    private final RuntimeStateRepository state;
    private final ToolPipeline tools;
    private final InteractionPort interactions;
    private final DelegationPort delegations;
    private final TodoReconciliationService todos;
    private final OutputContractValidator outputContract;
    private final RequiredArtifactChecker artifacts;
    private final CompletionPolicy policy;

    public DefaultCompletionGuard(
            RuntimeStateRepository state,
            ToolPipeline tools,
            InteractionPort interactions,
            DelegationPort delegations,
            TodoReconciliationService todos,
            OutputContractValidator outputContract,
            RequiredArtifactChecker artifacts,
            CompletionPolicy policy) {
        this.state = Objects.requireNonNull(state);
        this.tools = Objects.requireNonNull(tools);
        this.interactions = Objects.requireNonNull(interactions);
        this.delegations = Objects.requireNonNull(delegations);
        this.todos = Objects.requireNonNull(todos);
        this.outputContract = Objects.requireNonNull(outputContract);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.policy = Objects.requireNonNull(policy);
    }

    @Override
    public CompletionReadiness evaluate(AgentRun run, FinalAnswerDecision decision) {
        List<String> blockers = new ArrayList<>();
        if (!outputContract.isValid(run, decision)) blockers.add("invalid output contract");
        if (!artifacts.isSatisfied(run, decision)) blockers.add("required artifact missing");
        if (!policy.allows(run, decision)) blockers.add("completion policy denied");
        if (run.budget().isExceededBy(run.usage())) blockers.add("budget exceeded");
        if (tools.hasUncertainExecution(run)) blockers.add("uncertain tool execution");
        if (state.toolCalls(run.id()).stream().anyMatch(call -> !isTerminal(call.status())))
            blockers.add("pending tool call");
        todos.blocker(run).ifPresent(blockers::add);
        if (interactions.pending(run.id()).isPresent()) blockers.add("pending interaction");
        if (delegations.hasPendingChildren(run)) blockers.add("pending child run");
        return new CompletionReadiness(blockers.isEmpty(), blockers);
    }

    private static boolean isTerminal(ToolCallStatus status) {
        return status == ToolCallStatus.COMPLETED
                || status == ToolCallStatus.FAILED
                || status == ToolCallStatus.DENIED
                || status == ToolCallStatus.CANCELLED
                || status == ToolCallStatus.TIMEOUT;
    }
}
