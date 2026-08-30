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
        List<CompletionBlocker> blockers = new ArrayList<>();
        if (!outputContract.isValid(run, decision)) {
            boolean structured = state.configuration(run.configurationSnapshot())
                    .flatMap(configuration -> configuration.structuredOutput())
                    .isPresent();
            blockers.add(CompletionBlocker.recoverable(
                    structured ? "STRUCTURED_OUTPUT_INVALID" : "OUTPUT_CONTRACT_INVALID",
                    structured
                            ? "Structured final output does not satisfy the frozen schema."
                            : "Output contract is incomplete.",
                    "VALID_OUTPUT"));
        }
        if (!artifacts.isSatisfied(run, decision))
            blockers.add(CompletionBlocker.recoverable(
                    "REQUIRED_ARTIFACT_MISSING", "A required artifact is missing.", "REQUIRED_ARTIFACT"));
        CompletionPolicyResult policyResult = policy.evaluate(run, decision);
        blockers.addAll(policyResult.blockers());
        if (run.quotaPolicy().mode() == io.haifa.agent.core.run.QuotaMode.HARD_STOP
                && run.quotaPolicy().isExceededBy(run.usage()))
            blockers.add(CompletionBlocker.terminal("BUDGET_EXCEEDED", "Run budget is exhausted.", "BUDGET"));
        if (tools.hasUncertainExecution(run))
            blockers.add(CompletionBlocker.recoverable(
                    "UNCERTAIN_TOOL_EXECUTION", "A tool execution has an uncertain outcome.", "TOOL_RECONCILIATION"));
        if (state.toolCalls(run.id()).stream().anyMatch(call -> !isTerminal(call.status())))
            blockers.add(CompletionBlocker.recoverable(
                    "PENDING_TOOL_CALL", "A tool call is still pending.", "TERMINAL_TOOL_CALL"));
        todos.blocker(run)
                .ifPresent(value -> blockers.add(CompletionBlocker.recoverable(
                        "PENDING_TODO", "Required planned work is still pending.", "TODO_RECONCILIATION")));
        if (interactions.pending(run.id()).isPresent())
            blockers.add(CompletionBlocker.recoverable(
                    "PENDING_INTERACTION", "A user interaction is pending.", "INTERACTION_RESPONSE"));
        if (delegations.hasPendingChildren(run))
            blockers.add(CompletionBlocker.recoverable(
                    "PENDING_CHILD_RUN", "A delegated child run is pending.", "TERMINAL_CHILD_RUN"));
        return new CompletionReadiness(blockers.isEmpty(), blockers, policyResult.evidenceCodes());
    }

    private static boolean isTerminal(ToolCallStatus status) {
        return status == ToolCallStatus.COMPLETED
                || status == ToolCallStatus.FAILED
                || status == ToolCallStatus.DENIED
                || status == ToolCallStatus.CANCELLED
                || status == ToolCallStatus.TIMEOUT;
    }
}
