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
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.retry.PersistenceRetryPolicy;
import io.haifa.agent.runtime.core.retry.RetryExecutor;
import io.haifa.agent.runtime.core.storage.ExecutionAttemptRepository;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import io.haifa.agent.runtime.core.trace.TracePort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class AttemptExecutor {
    private final ExecutionAttemptRepository attempts;
    private final AgentLoop loop;
    private final RunTransitionCoordinator transitions;
    private final TimeProvider time;
    private final String owner;
    private final RetryExecutor persistenceRetries;
    private final PersistenceRetryPolicy persistenceRetry;
    private final TracePort trace;

    public AttemptExecutor(
            ExecutionAttemptRepository attempts,
            AgentLoop loop,
            RunTransitionCoordinator transitions,
            TimeProvider time,
            String owner,
            RetryExecutor persistenceRetries,
            PersistenceRetryPolicy persistenceRetry,
            TracePort trace) {
        this.attempts = Objects.requireNonNull(attempts);
        this.loop = Objects.requireNonNull(loop);
        this.transitions = Objects.requireNonNull(transitions);
        this.time = Objects.requireNonNull(time);
        this.owner = Objects.requireNonNull(owner);
        this.persistenceRetries = Objects.requireNonNull(persistenceRetries);
        this.persistenceRetry = Objects.requireNonNull(persistenceRetry);
        this.trace = Objects.requireNonNull(trace);
    }

    public void execute(AgentRun run, AgentRunExecutionAttempt attempt) {
        try {
            long expected = attempt.version();
            attempt.start(owner, time.now());
            persist(() -> attempts.save(attempt, expected));
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
            recordFailure(run, attempt, attemptError, error);
            if (!run.status().isTerminal()) transitions.failed(run, attemptError);
            finish(attempt, ExecutionAttemptStatus.FAILED, attemptError);
        }
    }

    private void finish(AgentRunExecutionAttempt attempt, ExecutionAttemptStatus status, AgentError error) {
        long expected = attempt.version();
        attempt.finish(status, time.now(), java.util.Optional.ofNullable(error));
        persist(() -> attempts.save(attempt, expected));
    }

    private void persist(Runnable work) {
        persistenceRetries.execute(
                () -> {
                    work.run();
                    return null;
                },
                persistenceRetry.policy());
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
        List<String> failureTypes = failureTypes(error);
        return new AgentError(
                new AgentErrorCode("RUNTIME_EXECUTION_FAILED"),
                AgentErrorCategory.INTERNAL,
                AgentErrorSeverity.ERROR,
                Retryability.UNKNOWN,
                "Agent execution failed",
                null,
                Map.of(
                        "exceptionType", failureTypes.getFirst(),
                        "rootExceptionType", failureTypes.getLast()),
                time.now());
    }

    private void recordFailure(
            AgentRun run, AgentRunExecutionAttempt attempt, AgentError attemptError, RuntimeException error) {
        List<String> failureTypes = failureTypes(error);
        trace.record(new RuntimeTraceEvent(
                attempt.attemptId().value(),
                run.id(),
                Optional.of(attempt.attemptId()),
                run.sessionId(),
                Optional.empty(),
                Optional.empty(),
                attempt.workerId(),
                0,
                RuntimePhase.ON_ERROR,
                "runtime.error",
                Map.of(
                        "errorCode", attemptError.code().value(),
                        "exceptionType", failureTypes.getFirst(),
                        "rootExceptionType", failureTypes.getLast(),
                        "failureTypes", failureTypes),
                time.now()));
    }

    private static List<String> failureTypes(Throwable error) {
        List<String> types = new ArrayList<>();
        Throwable current = error;
        while (current != null && types.size() < 8) {
            types.add(current.getClass().getSimpleName());
            current = current.getCause();
        }
        return List.copyOf(types);
    }
}
