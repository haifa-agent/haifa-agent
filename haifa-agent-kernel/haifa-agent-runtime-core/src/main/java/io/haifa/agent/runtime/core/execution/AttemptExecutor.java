package io.haifa.agent.runtime.core.execution;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptStatus;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.loop.AgentLoop;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.retry.PersistenceRetryPolicy;
import io.haifa.agent.runtime.core.retry.RetryExecutor;
import io.haifa.agent.runtime.core.storage.ExecutionAttemptRepository;
import io.haifa.agent.runtime.core.trace.FailureDiagnosticSink;
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
    private final IdentifierGenerator ids;
    private final FailureDiagnosticSink diagnostics;

    public AttemptExecutor(
            ExecutionAttemptRepository attempts,
            AgentLoop loop,
            RunTransitionCoordinator transitions,
            TimeProvider time,
            String owner,
            RetryExecutor persistenceRetries,
            PersistenceRetryPolicy persistenceRetry,
            TracePort trace,
            IdentifierGenerator ids,
            FailureDiagnosticSink diagnostics) {
        this.attempts = Objects.requireNonNull(attempts);
        this.loop = Objects.requireNonNull(loop);
        this.transitions = Objects.requireNonNull(transitions);
        this.time = Objects.requireNonNull(time);
        this.owner = Objects.requireNonNull(owner);
        this.persistenceRetries = Objects.requireNonNull(persistenceRetries);
        this.persistenceRetry = Objects.requireNonNull(persistenceRetry);
        this.trace = Objects.requireNonNull(trace);
        this.ids = Objects.requireNonNull(ids);
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    public void execute(AgentRun run, AgentRunExecutionAttempt attempt) {
        try {
            long expected = attempt.version();
            attempt.start(owner, time.now());
            persist(() -> attempts.save(attempt, expected));
            if (run.status() == AgentRunStatus.QUEUED || run.status() == AgentRunStatus.PENDING)
                transitions.started(run);
            loop.run(run, attempt);
            AgentError terminalError =
                    run.status() == AgentRunStatus.FAILED ? run.error().orElse(null) : null;
            if (terminalError != null) recordTerminalFailure(run, attempt, terminalError);
            finish(attempt, statusFor(run.status()), terminalError);
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
        if (error instanceof AgentExecutionFailureException classified) return classified.error();
        RuntimeLimitExceededException budgetExceeded = findFailure(error, RuntimeLimitExceededException.class);
        Map<String, Object> details = budgetExceeded == null
                ? Map.of()
                : Map.of(
                        "resource", budgetExceeded.resource(),
                        "limit", budgetExceeded.limit(),
                        "used", budgetExceeded.used());
        return new AgentError(
                budgetExceeded == null ? AgentErrorCode.RUNTIME_EXECUTION_FAILED : AgentErrorCode.RUN_BUDGET_EXCEEDED,
                details,
                ids.nextValue(),
                time.now());
    }

    private static <T extends Throwable> T findFailure(Throwable error, Class<T> failureType) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 8) {
            if (failureType.isInstance(current)) return failureType.cast(current);
            current = current.getCause();
            depth++;
        }
        return null;
    }

    private void recordFailure(
            AgentRun run, AgentRunExecutionAttempt attempt, AgentError attemptError, RuntimeException error) {
        List<String> failureTypes = failureTypes(error);
        RuntimeTraceEvent context = new RuntimeTraceEvent(
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
                        "errorCode", attemptError.code().wireCode(),
                        "diagnosticId", attemptError.diagnosticId() == null ? "" : attemptError.diagnosticId(),
                        "exceptionType", failureTypes.getFirst(),
                        "rootExceptionType", failureTypes.getLast(),
                        "failureTypes", failureTypes),
                time.now());
        recordTrace(context);
        try {
            diagnostics.record(context, error);
        } catch (RuntimeException ignored) {
            // Diagnostics are a best-effort projection and never alter authoritative Run state.
        }
    }

    private void recordTerminalFailure(AgentRun run, AgentRunExecutionAttempt attempt, AgentError error) {
        recordTrace(new RuntimeTraceEvent(
                attempt.attemptId().value(),
                run.id(),
                Optional.of(attempt.attemptId()),
                run.sessionId(),
                Optional.empty(),
                Optional.empty(),
                attempt.workerId(),
                0,
                RuntimePhase.ON_ERROR,
                "runtime.terminal-error",
                Map.of(
                        "errorCode",
                        error.code().wireCode(),
                        "diagnosticId",
                        error.diagnosticId() == null ? "" : error.diagnosticId()),
                time.now()));
    }

    private void recordTrace(RuntimeTraceEvent event) {
        try {
            trace.record(event);
        } catch (RuntimeException ignored) {
            // Trace delivery is observational and never alters authoritative Run state.
        }
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
