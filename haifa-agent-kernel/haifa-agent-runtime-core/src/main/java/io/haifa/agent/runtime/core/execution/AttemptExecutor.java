package io.haifa.agent.runtime.core.execution;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.api.ContextBuildException;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptStatus;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.guard.LoopDetectedException;
import io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException;
import io.haifa.agent.runtime.core.guard.RuntimeQuotaExceededException;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.loop.AgentLoop;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationException;
import io.haifa.agent.runtime.core.retry.PersistenceRetryPolicy;
import io.haifa.agent.runtime.core.retry.RetryExecutor;
import io.haifa.agent.runtime.core.storage.ExecutionAttemptRepository;
import io.haifa.agent.runtime.core.trace.FailureDiagnosticSink;
import io.haifa.agent.runtime.core.trace.RuntimeTraceContext;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import io.haifa.agent.runtime.core.trace.RuntimeTraceScope;
import io.haifa.agent.runtime.core.trace.RuntimeTraceStatus;
import io.haifa.agent.runtime.core.trace.TraceIdentifierGenerator;
import io.haifa.agent.runtime.core.trace.TracePort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AttemptExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(AttemptExecutor.class);
    private final ExecutionAttemptRepository attempts;
    private final AgentLoop loop;
    private final RunTransitionCoordinator transitions;
    private final TimeProvider time;
    private final String owner;
    private final RetryExecutor persistenceRetries;
    private final PersistenceRetryPolicy persistenceRetry;
    private final TracePort trace;
    private final TraceIdentifierGenerator traceIds;
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
            TraceIdentifierGenerator traceIds,
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
        this.traceIds = Objects.requireNonNull(traceIds);
        this.ids = Objects.requireNonNull(ids);
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    public void execute(AgentRun run, AgentRunExecutionAttempt attempt) {
        RuntimeTraceContext traceContext = RuntimeTraceContext.forAttempt(
                traceIds.nextTraceId(), attempt.attemptId(), attempt.workerId().or(() -> Optional.of(owner)));
        try {
            long expected = attempt.version();
            attempt.start(owner, time.now());
            persist(() -> attempts.save(attempt, expected));
            recordAttemptStarted(run, traceContext);
            boolean startsRun = run.status() == AgentRunStatus.QUEUED || run.status() == AgentRunStatus.PENDING;
            if (startsRun) {
                transitions.started(run);
                recordRunStarted(run, traceContext);
            }
            loop.run(run, attempt, traceContext);
            AgentError terminalError =
                    run.status() == AgentRunStatus.FAILED ? run.error().orElse(null) : null;
            if (terminalError != null) recordTerminalFailure(run, attempt, traceContext, terminalError);
            recordRunTerminal(run, traceContext);
            finish(attempt, statusFor(run.status()), terminalError);
        } catch (CancellationObservedException cancelled) {
            if (!run.status().isTerminal()) {
                transitions.cancelled(
                        run,
                        new io.haifa.agent.core.run.RunTerminationReason(
                                "USER_CANCELLED", "Cancellation observed at tool safe point"));
            }
            recordRunTerminal(run, traceContext);
            finish(attempt, ExecutionAttemptStatus.CANCELLED, null);
        } catch (RuntimeException error) {
            AgentError attemptError = safeError(error);
            recordFailure(run, attempt, traceContext, attemptError, error);
            if (!run.status().isTerminal()) transitions.failed(run, attemptError);
            recordRunTerminal(run, traceContext);
            finish(attempt, ExecutionAttemptStatus.FAILED, attemptError);
        }
    }

    private void recordAttemptStarted(AgentRun run, RuntimeTraceContext context) {
        recordTrace(new RuntimeTraceEvent(
                context.traceId(),
                run.id(),
                context.attemptId(),
                run.sessionId(),
                Optional.empty(),
                Optional.empty(),
                context.workerId(),
                OptionalInt.empty(),
                RuntimePhase.BEFORE_RUN,
                "attempt.started",
                RuntimeTraceScope.ATTEMPT,
                RuntimeTraceStatus.STARTED,
                Map.of(),
                time.now()));
    }

    private void recordRunStarted(AgentRun run, RuntimeTraceContext context) {
        recordTrace(new RuntimeTraceEvent(
                context.traceId(),
                run.id(),
                context.attemptId(),
                run.sessionId(),
                Optional.empty(),
                Optional.empty(),
                context.workerId(),
                OptionalInt.empty(),
                RuntimePhase.BEFORE_RUN,
                "run.started",
                RuntimeTraceScope.RUN,
                RuntimeTraceStatus.STARTED,
                Map.of("runStatus", run.status().name()),
                time.now()));
    }

    private void recordRunTerminal(AgentRun run, RuntimeTraceContext context) {
        String operation;
        RuntimeTraceStatus status;
        RuntimePhase phase;
        switch (run.status()) {
            case COMPLETED -> {
                operation = "run.completed";
                status = RuntimeTraceStatus.SUCCESS;
                phase = RuntimePhase.AFTER_COMPLETION;
            }
            case FAILED -> {
                operation = "run.failed";
                status = RuntimeTraceStatus.FAILURE;
                phase = RuntimePhase.ON_ERROR;
            }
            case CANCELLED -> {
                operation = "run.cancelled";
                status = RuntimeTraceStatus.UNKNOWN;
                phase = RuntimePhase.ON_ERROR;
            }
            case TIMEOUT -> {
                operation = "run.timeout";
                status = RuntimeTraceStatus.FAILURE;
                phase = RuntimePhase.ON_ERROR;
            }
            default -> {
                return;
            }
        }
        recordTrace(new RuntimeTraceEvent(
                context.traceId(),
                run.id(),
                context.attemptId(),
                run.sessionId(),
                Optional.empty(),
                Optional.empty(),
                context.workerId(),
                OptionalInt.empty(),
                phase,
                operation,
                RuntimeTraceScope.RUN,
                status,
                Map.of("runStatus", run.status().name()),
                time.now()));
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
        RuntimeQuotaExceededException quotaExceeded = findFailure(error, RuntimeQuotaExceededException.class);
        RuntimeLimitExceededException budgetExceeded = findFailure(error, RuntimeLimitExceededException.class);
        ContextBuildException contextBuild = findFailure(error, ContextBuildException.class);
        LoopDetectedException loopDetected = findFailure(error, LoopDetectedException.class);
        ModelContinuationException continuationFailure = findFailure(error, ModelContinuationException.class);
        Map<String, Object> details;
        if (continuationFailure != null) {
            details = Map.of(
                    "continuationFailure", continuationFailure.failure().name(),
                    "continuationMessage", continuationFailure.getMessage());
        } else if (loopDetected != null) {
            details = Map.of("loopReason", loopDetected.reason().name());
        } else if (quotaExceeded != null) {
            details = Map.of(
                    "resource", quotaExceeded.resource(),
                    "limit", quotaExceeded.limit(),
                    "used", quotaExceeded.used());
        } else if (budgetExceeded != null) {
            details = Map.of(
                    "resource", budgetExceeded.resource(),
                    "limit", budgetExceeded.limit(),
                    "used", budgetExceeded.used());
        } else if (contextBuild != null) {
            details = Map.of("contextFailure", contextBuild.failure().name());
        } else {
            details = Map.of();
        }
        return new AgentError(
                classifiedErrorCode(quotaExceeded, budgetExceeded, contextBuild, loopDetected, continuationFailure),
                details,
                ids.nextValue(),
                time.now());
    }

    static AgentErrorCode classifiedErrorCode(
            RuntimeLimitExceededException budgetExceeded, ContextBuildException contextBuild) {
        return classifiedErrorCode(null, budgetExceeded, contextBuild, null, null);
    }

    static AgentErrorCode classifiedErrorCode(
            RuntimeLimitExceededException budgetExceeded,
            ContextBuildException contextBuild,
            LoopDetectedException loopDetected) {
        return classifiedErrorCode(null, budgetExceeded, contextBuild, loopDetected, null);
    }

    static AgentErrorCode classifiedErrorCode(
            RuntimeQuotaExceededException quotaExceeded,
            RuntimeLimitExceededException budgetExceeded,
            ContextBuildException contextBuild,
            LoopDetectedException loopDetected) {
        return classifiedErrorCode(quotaExceeded, budgetExceeded, contextBuild, loopDetected, null);
    }

    static AgentErrorCode classifiedErrorCode(
            RuntimeQuotaExceededException quotaExceeded,
            RuntimeLimitExceededException budgetExceeded,
            ContextBuildException contextBuild,
            LoopDetectedException loopDetected,
            ModelContinuationException continuationFailure) {
        if (continuationFailure != null) return AgentErrorCode.CROSS_MODEL_CONTINUATION_INVALID;
        if (loopDetected != null) return AgentErrorCode.AGENT_LOOP_DETECTED;
        if (quotaExceeded != null) {
            return switch (quotaExceeded.resource()) {
                case "inputTokens" -> AgentErrorCode.RUN_INPUT_QUOTA_EXHAUSTED;
                case "outputTokens" -> AgentErrorCode.RUN_OUTPUT_QUOTA_EXHAUSTED;
                case "costMinorUnits" -> AgentErrorCode.RUN_COST_QUOTA_EXHAUSTED;
                default -> AgentErrorCode.RUN_BUDGET_EXCEEDED;
            };
        }
        if (budgetExceeded != null) {
            return switch (budgetExceeded.resource()) {
                case "iterations",
                        "modelCalls",
                        "toolCalls",
                        "childRuns",
                        "wallTimeMillis",
                        "idleTimeMillis",
                        "depth" -> AgentErrorCode.RUN_EXECUTION_LIMIT_EXCEEDED;
                default -> AgentErrorCode.RUN_BUDGET_EXCEEDED;
            };
        }
        if (contextBuild == null) return AgentErrorCode.RUNTIME_EXECUTION_FAILED;
        return switch (contextBuild.failure()) {
            case MODEL_WINDOW_TOO_SMALL, REQUIRED_CONTEXT_TOO_LARGE -> AgentErrorCode.MODEL_CONTEXT_TOO_LONG;
            case UNSUPPORTED_CONTEXT_CONTENT -> AgentErrorCode.RUNTIME_EXECUTION_FAILED;
        };
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
            AgentRun run,
            AgentRunExecutionAttempt attempt,
            RuntimeTraceContext traceContext,
            AgentError attemptError,
            RuntimeException error) {
        List<String> failureTypes = failureTypes(error);
        ContextBuildException contextBuild = findFailure(error, ContextBuildException.class);
        LOGGER.warn(
                "event=runtime.failure runId={} attemptId={} errorCode={} diagnosticId={} failureTypes={} contextFailure={}",
                run.id().value(),
                attempt.attemptId().value(),
                attemptError.code().wireCode(),
                attemptError.diagnosticId() == null ? "" : attemptError.diagnosticId(),
                failureTypes,
                contextBuild == null ? "" : contextBuild.failure().name());
        RuntimeTraceEvent context = new RuntimeTraceEvent(
                traceContext.traceId(),
                run.id(),
                Optional.of(attempt.attemptId()),
                run.sessionId(),
                Optional.empty(),
                Optional.empty(),
                attempt.workerId(),
                OptionalInt.empty(),
                RuntimePhase.ON_ERROR,
                "runtime.error",
                RuntimeTraceScope.ATTEMPT,
                RuntimeTraceStatus.FAILURE,
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

    private void recordTerminalFailure(
            AgentRun run, AgentRunExecutionAttempt attempt, RuntimeTraceContext traceContext, AgentError error) {
        recordTrace(new RuntimeTraceEvent(
                traceContext.traceId(),
                run.id(),
                Optional.of(attempt.attemptId()),
                run.sessionId(),
                Optional.empty(),
                Optional.empty(),
                attempt.workerId(),
                OptionalInt.empty(),
                RuntimePhase.ON_ERROR,
                "runtime.terminal-error",
                RuntimeTraceScope.ATTEMPT,
                RuntimeTraceStatus.FAILURE,
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
