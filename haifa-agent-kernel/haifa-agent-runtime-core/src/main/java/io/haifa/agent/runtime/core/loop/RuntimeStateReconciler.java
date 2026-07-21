package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.step.AgentStepStatus;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptStatus;
import io.haifa.agent.runtime.core.execution.ExecutionOwnershipPort;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.storage.ExecutionAttemptRepository;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.tool.ToolPipeline;
import java.util.Objects;

/** Verifies persisted cursors and executor ownership before a new loop iteration may begin. */
public final class RuntimeStateReconciler {
    private final RuntimeStateRepository state;
    private final ExecutionAttemptRepository attempts;
    private final InteractionPort interactions;
    private final ToolPipeline tools;
    private final TimeProvider time;
    private final ExecutionOwnershipPort ownership;

    public RuntimeStateReconciler(
            RuntimeStateRepository state,
            ExecutionAttemptRepository attempts,
            InteractionPort interactions,
            ToolPipeline tools,
            TimeProvider time,
            ExecutionOwnershipPort ownership) {
        this.state = Objects.requireNonNull(state);
        this.attempts = Objects.requireNonNull(attempts);
        this.interactions = Objects.requireNonNull(interactions);
        this.tools = Objects.requireNonNull(tools);
        this.time = Objects.requireNonNull(time);
        this.ownership = Objects.requireNonNull(ownership);
    }

    public void reconcile(AgentRun run, AgentRunExecutionAttempt attempt) {
        if (run.status() != AgentRunStatus.RUNNING && run.status() != AgentRunStatus.SUSPENDING) {
            throw new IllegalStateException("run is not executable from " + run.status());
        }
        AgentRunExecutionAttempt active = attempts.activeFor(run.id())
                .orElseThrow(() -> new IllegalStateException("run has no active execution attempt"));
        if (!active.attemptId().equals(attempt.attemptId()) || active.status() != ExecutionAttemptStatus.RUNNING) {
            throw new IllegalStateException("execution attempt no longer owns the run");
        }
        if (!ownership.stillOwned(active)) throw new IllegalStateException("execution lease is no longer owned");
        if (state.configuration(run.configurationSnapshot()).isEmpty()) {
            throw new IllegalStateException("run configuration snapshot is unavailable or corrupt");
        }
        if (interactions.pending(run.id()).isPresent()) {
            throw new IllegalStateException("mandatory interaction must be resolved before execution");
        }
        if (state.steps(run.id()).stream().anyMatch(step -> !isTerminal(step.status()))) {
            throw new IllegalStateException("previous runtime step is not durably terminal");
        }
        if (state.toolCalls(run.id()).stream().anyMatch(call -> !isTerminal(call.status()))) {
            throw new IllegalStateException("previous tool call has an uncertain or non-terminal state");
        }
        if (tools.hasUncertainExecution(run)) {
            throw new IllegalStateException("tool execution journal requires controlled reconciliation");
        }
        long expected = attempt.version();
        attempt.heartbeat(time.now());
        attempts.save(attempt, expected);
    }

    private static boolean isTerminal(AgentStepStatus status) {
        return status == AgentStepStatus.COMPLETED
                || status == AgentStepStatus.FAILED
                || status == AgentStepStatus.CANCELLED
                || status == AgentStepStatus.SKIPPED;
    }

    private static boolean isTerminal(ToolCallStatus status) {
        return status == ToolCallStatus.COMPLETED
                || status == ToolCallStatus.FAILED
                || status == ToolCallStatus.DENIED
                || status == ToolCallStatus.CANCELLED
                || status == ToolCallStatus.TIMEOUT;
    }
}
