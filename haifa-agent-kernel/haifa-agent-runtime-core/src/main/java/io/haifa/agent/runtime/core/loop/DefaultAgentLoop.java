package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.checkpoint.CheckpointType;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunUsageDelta;
import io.haifa.agent.core.run.RunTerminationReason;
import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.step.AgentStepError;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.step.AgentStepResult;
import io.haifa.agent.core.step.AgentStepType;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.checkpoint.CheckpointManager;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.control.RunControlSignal;
import io.haifa.agent.runtime.core.control.SafePoint;
import io.haifa.agent.runtime.core.decision.AgentDecision;
import io.haifa.agent.runtime.core.decision.AgentLoopDirective;
import io.haifa.agent.runtime.core.decision.DecisionExecutor;
import io.haifa.agent.runtime.core.decision.DecisionValidator;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import io.haifa.agent.runtime.core.guard.AgentLoopGuard;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.middleware.AgentRuntimeMiddlewareChain;
import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareContext;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.model.ModelResponseInterpreter;
import io.haifa.agent.runtime.core.model.ModelSelector;
import io.haifa.agent.runtime.core.retry.ModelRetryPolicy;
import io.haifa.agent.runtime.core.retry.RetryExecutor;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import io.haifa.agent.runtime.core.trace.TracePort;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Persisted, guarded and resumable observe-decide-act Agent loop. */
public final class DefaultAgentLoop implements AgentLoop {
    private final RunControlRegistry controls;
    private final List<AgentLoopGuard> guards;
    private final ContextBuilder contextBuilder;
    private final ModelSelector models;
    private final ModelResponseInterpreter interpreter;
    private final DecisionValidator validator;
    private final DecisionExecutor decisionExecutor;
    private final CheckpointManager checkpoints;
    private final RunTransitionCoordinator transitions;
    private final RuntimeStateRepository state;
    private final RuntimeEventAppender events;
    private final RetryExecutor retries;
    private final ModelRetryPolicy modelRetryPolicy;
    private final IdentifierGenerator ids;
    private final TimeProvider time;
    private final TracePort trace;
    private final RuntimeStateReconciler reconciler;
    private final AgentRuntimeMiddlewareChain middleware;

    public DefaultAgentLoop(
            RunControlRegistry controls,
            List<AgentLoopGuard> guards,
            ContextBuilder contextBuilder,
            ModelSelector models,
            ModelResponseInterpreter interpreter,
            DecisionValidator validator,
            DecisionExecutor decisionExecutor,
            CheckpointManager checkpoints,
            RunTransitionCoordinator transitions,
            RuntimeStateRepository state,
            RuntimeEventAppender events,
            RetryExecutor retries,
            ModelRetryPolicy modelRetryPolicy,
            IdentifierGenerator ids,
            TimeProvider time,
            TracePort trace,
            RuntimeStateReconciler reconciler,
            AgentRuntimeMiddlewareChain middleware) {
        this.controls = Objects.requireNonNull(controls);
        this.guards = List.copyOf(guards);
        this.contextBuilder = Objects.requireNonNull(contextBuilder);
        this.models = Objects.requireNonNull(models);
        this.interpreter = Objects.requireNonNull(interpreter);
        this.validator = Objects.requireNonNull(validator);
        this.decisionExecutor = Objects.requireNonNull(decisionExecutor);
        this.checkpoints = Objects.requireNonNull(checkpoints);
        this.transitions = Objects.requireNonNull(transitions);
        this.state = Objects.requireNonNull(state);
        this.events = Objects.requireNonNull(events);
        this.retries = Objects.requireNonNull(retries);
        this.modelRetryPolicy = Objects.requireNonNull(modelRetryPolicy);
        this.ids = Objects.requireNonNull(ids);
        this.time = Objects.requireNonNull(time);
        this.trace = Objects.requireNonNull(trace);
        this.reconciler = Objects.requireNonNull(reconciler);
        this.middleware = Objects.requireNonNull(middleware);
    }

    @Override
    public AgentLoopResult run(AgentRun run, AgentRunExecutionAttempt attempt) {
        var restored = checkpoints.restoreLatest(run);
        AgentLoopContext progress = restored.map(
                        value -> new AgentLoopContext(value.nextIteration(), value.decisionFingerprints()))
                .orElseGet(() -> new AgentLoopContext(1, List.of()));
        middleware.apply(RuntimePhase.BEFORE_RUN, new RuntimeMiddlewareContext(run, state));
        String traceId = ids.nextValue();
        while (run.status() == AgentRunStatus.RUNNING || run.status() == AgentRunStatus.SUSPENDING) {
            AgentLoopIteration iteration = new AgentLoopIteration(progress.iteration(), time.now());
            if (applyControl(run, progress, SafePoint.BEFORE_ITERATION, progress.iteration() - 1)) {
                return new AgentLoopResult(run.status(), iteration, AgentLoopDirective.STOP);
            }
            if (Duration.between(run.createdAt(), time.now()).toMillis()
                    > run.limits().maxWallTimeMillis()) {
                transitions.timedOut(
                        run, new RunTerminationReason("WALL_TIME_EXCEEDED", "Run wall-time limit exceeded"));
                return new AgentLoopResult(run.status(), iteration, AgentLoopDirective.STOP);
            }
            if (Duration.between(run.updatedAt(), time.now()).toMillis()
                    > run.limits().maxIdleTimeMillis()) {
                transitions.timedOut(
                        run, new RunTerminationReason("IDLE_TIME_EXCEEDED", "Run idle-time limit exceeded"));
                return new AgentLoopResult(run.status(), iteration, AgentLoopDirective.STOP);
            }
            guards.forEach(guard -> guard.check(run, progress));
            reconciler.reconcile(run, attempt);
            trace.record(new RuntimeTraceEvent(
                    traceId,
                    run.id(),
                    java.util.Optional.of(attempt.attemptId()),
                    run.sessionId(),
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    attempt.workerId(),
                    progress.iteration(),
                    RuntimePhase.BEFORE_CONTEXT_BUILD,
                    "loop.iteration",
                    Map.of("iteration", progress.iteration()),
                    time.now()));

            ContextBuildResult built = contextBuilder.build(run, progress);
            RuntimeMiddlewareContext middlewareContext = built.middlewareContext();
            middleware.apply(RuntimePhase.BEFORE_MODEL_CALL, middlewareContext);
            if (applyControl(run, progress, SafePoint.AFTER_CONTEXT_BUILD, progress.iteration() - 1)) {
                return new AgentLoopResult(run.status(), iteration, AgentLoopDirective.STOP);
            }
            AgentStep modelStep = new AgentStep(
                    new AgentStepId(ids.nextValue()),
                    run.id(),
                    null,
                    null,
                    AgentStepType.MODEL_CALL,
                    state.steps(run.id()).size() + 1,
                    time.now());
            state.appendStep(modelStep);
            modelStep.start(time.now());
            AgentDecision decision;
            try {
                var response = retries.execute(
                        () -> {
                            if (run.usage().modelCalls() >= run.budget().maxModelCalls()) {
                                throw new io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException(
                                        "model call budget exhausted");
                            }
                            transitions.usage(run, new AgentRunUsageDelta(0, 0, 0, 1, 0, 0, 0, 0));
                            return models.select(run).invoke(built.request());
                        },
                        modelRetryPolicy.policy());
                transitions.usage(
                        run,
                        new AgentRunUsageDelta(
                                response.inputTokens(),
                                response.outputTokens(),
                                0,
                                0,
                                0,
                                0,
                                response.costMinorUnits(),
                                0));
                if (run.budget().isExceededBy(run.usage())) {
                    throw new IllegalStateException("run budget exceeded by model usage");
                }
                middleware.apply(RuntimePhase.AFTER_MODEL_CALL, middlewareContext);
                decision = interpreter.interpret(response);
                validator.validate(run, decision);
            } catch (RuntimeException error) {
                failModelStep(modelStep, error);
                middleware.apply(RuntimePhase.ON_ERROR, middlewareContext);
                throw error;
            }
            String fingerprint = decision.getClass().getSimpleName() + ":" + decision;
            progress.record(fingerprint);
            modelStep.complete(
                    new AgentStepResult(
                            "Model decision: " + decision.getClass().getSimpleName(),
                            Map.of("fingerprint", fingerprint),
                            List.of()),
                    time.now());

            if (applyControl(run, progress, SafePoint.AFTER_MODEL_CALL, progress.iteration() - 1)) {
                return new AgentLoopResult(run.status(), iteration, AgentLoopDirective.STOP);
            }

            if (decision instanceof FinalAnswerDecision) {
                middleware.apply(RuntimePhase.BEFORE_COMPLETION, middlewareContext);
                checkpoints.capture(run, progress.iteration(), progress.fingerprints(), CheckpointType.AUTOMATIC);
            }
            middleware.apply(RuntimePhase.BEFORE_DECISION_EXECUTION, middlewareContext);
            AgentLoopDirective directive;
            try {
                directive = decisionExecutor.execute(run, decision, progress);
            } catch (RuntimeException error) {
                middleware.apply(RuntimePhase.ON_ERROR, middlewareContext);
                throw error;
            }
            middleware.apply(RuntimePhase.AFTER_DECISION_EXECUTION, middlewareContext);
            if (decision instanceof FinalAnswerDecision && run.status() == AgentRunStatus.COMPLETED) {
                middleware.apply(RuntimePhase.AFTER_COMPLETION, middlewareContext);
            }
            events.append(
                    run.id(),
                    "loop.iteration-persisted",
                    Map.of("iteration", progress.iteration(), "directive", directive.name()),
                    time.now());
            progress.recordProgress(progressSignature(run));
            checkpoints.capture(
                    run,
                    progress.iteration(),
                    progress.fingerprints(),
                    directive == AgentLoopDirective.WAIT ? CheckpointType.INTERACTION : CheckpointType.AUTOMATIC);
            if (directive != AgentLoopDirective.CONTINUE)
                return new AgentLoopResult(run.status(), iteration, directive);
            if (applyControl(run, progress, SafePoint.AFTER_DECISION_PERSISTED, progress.iteration())) {
                return new AgentLoopResult(run.status(), iteration, AgentLoopDirective.STOP);
            }
            progress.next();
        }
        return new AgentLoopResult(
                run.status(),
                new AgentLoopIteration(Math.max(1, progress.iteration()), time.now()),
                AgentLoopDirective.STOP);
    }

    private boolean applyControl(AgentRun run, AgentLoopContext progress, SafePoint safePoint, int completedIteration) {
        RunControlSignal signal = controls.signal(run.id());
        if (signal == RunControlSignal.CANCEL) {
            transitions.cancelled(run, new RunTerminationReason("USER_CANCELLED", "Cancellation requested"));
            controls.clear(run.id());
            return true;
        }
        if (signal == RunControlSignal.TIMEOUT) {
            transitions.timedOut(run, new RunTerminationReason("CONTROL_TIMEOUT", "Runtime timeout signal observed"));
            controls.clear(run.id());
            return true;
        }
        if (signal == RunControlSignal.LEASE_LOST
                || signal == RunControlSignal.ADMIN_STOP
                || signal == RunControlSignal.PARENT_CANCELLED) {
            transitions.cancelled(run, new RunTerminationReason(signal.name(), "Runtime stop signal observed"));
            controls.clear(run.id());
            return true;
        }
        if (signal == RunControlSignal.PAUSE || run.status() == AgentRunStatus.SUSPENDING) {
            if (run.status() == AgentRunStatus.RUNNING) transitions.requestPause(run);
            events.append(
                    run.id(),
                    "run.safe-point",
                    Map.of("safePoint", safePoint.name(), "iteration", progress.iteration()),
                    time.now());
            checkpoints.capture(run, Math.max(0, completedIteration), progress.fingerprints(), CheckpointType.MANUAL);
            transitions.suspended(run);
            controls.clear(run.id());
            return true;
        }
        return false;
    }

    private void failModelStep(AgentStep step, RuntimeException error) {
        if (step.status() != io.haifa.agent.core.step.AgentStepStatus.RUNNING) return;
        step.fail(
                new AgentStepError(new io.haifa.agent.core.error.AgentError(
                        new io.haifa.agent.core.error.AgentErrorCode("MODEL_CALL_FAILED"),
                        io.haifa.agent.core.error.AgentErrorCategory.MODEL,
                        io.haifa.agent.core.error.AgentErrorSeverity.ERROR,
                        io.haifa.agent.core.error.Retryability.UNKNOWN,
                        "Model call or response validation failed",
                        null,
                        Map.of("exceptionType", error.getClass().getSimpleName()),
                        time.now())),
                time.now());
    }

    private String progressSignature(AgentRun run) {
        long completedTools = state.toolCalls(run.id()).stream()
                .filter(call -> call.status() == io.haifa.agent.core.tool.ToolCallStatus.COMPLETED)
                .count();
        long artifacts = state.toolCalls(run.id()).stream()
                .flatMap(call -> call.result().stream())
                .mapToLong(result -> result.artifacts().size())
                .sum();
        String todos = state.plan(run.id())
                .map(plan -> plan.items().stream()
                        .map(item -> item.id() + ":" + item.status())
                        .toList()
                        .toString())
                .orElse("none");
        return state.messages(run.id()).size() + "|" + completedTools + "|" + artifacts + "|"
                + run.usage().childRuns() + "|" + todos + "|" + run.status();
    }
}
