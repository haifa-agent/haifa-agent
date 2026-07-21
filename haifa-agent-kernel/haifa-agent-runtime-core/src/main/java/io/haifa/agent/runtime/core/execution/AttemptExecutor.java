package io.haifa.agent.runtime.core.execution;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCategory;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.error.AgentErrorSeverity;
import io.haifa.agent.core.error.Retryability;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptStatus;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.loop.AgentLoop;
import io.haifa.agent.runtime.core.storage.ExecutionAttemptRepository;
import java.util.Map;
import java.util.Objects;

public final class AttemptExecutor {
    private final ExecutionAttemptRepository attempts;
    private final AgentLoop loop;
    private final RunTransitionCoordinator transitions;
    private final TimeProvider time;
    private final String owner;

    public AttemptExecutor(
            ExecutionAttemptRepository attempts,
            AgentLoop loop,
            RunTransitionCoordinator transitions,
            TimeProvider time,
            String owner) {
        this.attempts = Objects.requireNonNull(attempts);
        this.loop = Objects.requireNonNull(loop);
        this.transitions = Objects.requireNonNull(transitions);
        this.time = Objects.requireNonNull(time);
        this.owner = Objects.requireNonNull(owner);
    }

    public void execute(AgentRun run, AgentRunExecutionAttempt attempt) {
        long expected = attempt.version();
        attempt.start(owner, time.now());
        attempts.save(attempt, expected);
        try {
            if (run.status() == AgentRunStatus.QUEUED || run.status() == AgentRunStatus.PENDING)
                transitions.started(run);
            loop.run(run, attempt);
            finish(attempt, statusFor(run.status()), null);
        } catch (CancellationObservedException cancelled) {
            if (!run.status().isTerminal()) {
                transitions.cancelled(
                        run,
                        new io.haifa.agent.core.run.RunTerminationReason(
                                "USER_CANCELLED", "Cancellation observed at tool safe point"));
            }
            finish(attempt, ExecutionAttemptStatus.CANCELLED, null);
        } catch (RuntimeException error) {
            AgentError attemptError = safeError(error);
            if (!run.status().isTerminal()) transitions.failed(run, attemptError);
            finish(attempt, ExecutionAttemptStatus.FAILED, attemptError);
        }
    }

    private void finish(AgentRunExecutionAttempt attempt, ExecutionAttemptStatus status, AgentError error) {
        long expected = attempt.version();
        attempt.finish(status, time.now(), java.util.Optional.ofNullable(error));
        attempts.save(attempt, expected);
    }

    private static ExecutionAttemptStatus statusFor(AgentRunStatus status) {
        return switch (status) {
            case COMPLETED -> ExecutionAttemptStatus.SUCCEEDED;
            case SUSPENDED, WAITING_APPROVAL, WAITING_INTERACTION -> ExecutionAttemptStatus.PAUSED;
            case CANCELLED -> ExecutionAttemptStatus.CANCELLED;
            default -> ExecutionAttemptStatus.FAILED;
        };
    }

    private AgentError safeError(RuntimeException error) {
        return new AgentError(
                new AgentErrorCode("RUNTIME_EXECUTION_FAILED"),
                AgentErrorCategory.INTERNAL,
                AgentErrorSeverity.ERROR,
                Retryability.UNKNOWN,
                "Agent execution failed",
                null,
                Map.of("exceptionType", error.getClass().getSimpleName()),
                time.now());
    }
}
