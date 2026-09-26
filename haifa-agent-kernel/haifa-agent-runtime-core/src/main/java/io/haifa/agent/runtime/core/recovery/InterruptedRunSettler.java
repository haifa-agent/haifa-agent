package io.haifa.agent.runtime.core.recovery;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.step.AgentStepStatus;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptStatus;
import io.haifa.agent.runtime.core.execution.AgentExecutionFailureException;
import io.haifa.agent.runtime.core.loop.ToolRecoveryCoordinator;
import io.haifa.agent.runtime.core.storage.ExecutionAttemptRepository;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Settles the durable facts of a Run whose executor disappeared, without dispatching any work.
 *
 * <p>The abandoned attempt is closed, saved Tool results are reconciled, every open Tool Call is cancelled with an
 * explicit "do not repeat" result and open steps are cancelled. The caller records the returned error as the Run's
 * terminal failure inside the same Unit of Work.
 */
public final class InterruptedRunSettler {
    private static final Set<ToolCallStatus> OPEN_TOOL_CALLS = EnumSet.of(
            ToolCallStatus.REQUESTED,
            ToolCallStatus.VALIDATING,
            ToolCallStatus.POLICY_CHECK,
            ToolCallStatus.WAITING_APPROVAL,
            ToolCallStatus.APPROVED,
            ToolCallStatus.RUNNING);

    private final ExecutionAttemptRepository attempts;
    private final RuntimeStateRepository state;
    private final ToolRecoveryCoordinator toolRecovery;
    private final IdentifierGenerator ids;
    private final TimeProvider time;

    public InterruptedRunSettler(
            ExecutionAttemptRepository attempts,
            RuntimeStateRepository state,
            ToolRecoveryCoordinator toolRecovery,
            IdentifierGenerator ids,
            TimeProvider time) {
        this.attempts = Objects.requireNonNull(attempts);
        this.state = Objects.requireNonNull(state);
        this.toolRecovery = Objects.requireNonNull(toolRecovery);
        this.ids = Objects.requireNonNull(ids);
        this.time = Objects.requireNonNull(time);
    }

    public AgentError settle(AgentRun current, AgentRunExecutionAttempt active) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(active, "active must not be null");
        long expected = active.version();
        active.finish(ExecutionAttemptStatus.ABANDONED, time.now(), Optional.empty());
        attempts.save(active, expected);
        AgentError error = new AgentError(
                AgentErrorCode.RUNTIME_EXECUTION_INTERRUPTED,
                Map.of("reason", "EXECUTOR_LOST", "automaticResume", false),
                ids.nextValue(),
                time.now());
        try {
            toolRecovery.reconcile(current);
        } catch (AgentExecutionFailureException unknown) {
            error = unknown.error();
        }
        for (var call : state.toolCalls(current.id())) {
            if (!OPEN_TOOL_CALLS.contains(call.status())) continue;
            call.cancel(time.now());
            state.appendToolCall(call);
            state.appendSessionMessage(new SessionMessageDraft(
                    new AgentMessageId(ids.nextValue()),
                    current.sessionId(),
                    Optional.of(current.id()),
                    Optional.empty(),
                    MessageRole.TOOL,
                    MessageStatus.COMPLETED,
                    MessageVisibility.AGENT_VISIBLE,
                    List.of(
                            new ToolResultPart(
                                    call.id(),
                                    call.providerCorrelationId(),
                                    "Execution interrupted before a confirmed result; do not automatically repeat this operation")),
                    Map.of("interrupted", true),
                    time.now()));
        }
        for (var step : state.steps(current.id())) {
            if (step.status() == AgentStepStatus.PENDING
                    || step.status() == AgentStepStatus.RUNNING
                    || step.status() == AgentStepStatus.WAITING) {
                step.cancel(time.now());
                state.appendStep(step);
            }
        }
        return error;
    }
}
