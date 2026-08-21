package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.checkpoint.CheckpointType;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunUsageDelta;
import io.haifa.agent.core.run.RunTerminationReason;
import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.step.AgentStepError;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.step.AgentStepResult;
import io.haifa.agent.core.step.AgentStepType;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelRequestId;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.checkpoint.CheckpointManager;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.control.RunControlSignal;
import io.haifa.agent.runtime.core.control.SafePoint;
import io.haifa.agent.runtime.core.decision.AgentDecision;
import io.haifa.agent.runtime.core.decision.AgentLoopDirective;
import io.haifa.agent.runtime.core.decision.DecisionExecutor;
import io.haifa.agent.runtime.core.decision.DecisionValidator;
import io.haifa.agent.runtime.core.decision.DelegationDecision;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import io.haifa.agent.runtime.core.decision.ToolCallDecision;
import io.haifa.agent.runtime.core.execution.AgentExecutionFailureException;
import io.haifa.agent.runtime.core.guard.AgentLoopGuard;
import io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException;
import io.haifa.agent.runtime.core.input.RunInputApplier;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.middleware.AgentRuntimeMiddlewareChain;
import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareContext;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;
import io.haifa.agent.runtime.core.model.FrozenModelInvoker;
import io.haifa.agent.runtime.core.model.ModelInvocationResult;
import io.haifa.agent.runtime.core.recovery.RecoveryController;
import io.haifa.agent.runtime.core.recovery.RecoveryDirective;
import io.haifa.agent.runtime.core.recovery.RunBudgetSnapshot;
import io.haifa.agent.runtime.core.recovery.TerminalFailureSummary;
import io.haifa.agent.runtime.core.retry.ModelRetryPolicy;
import io.haifa.agent.runtime.core.retry.RetryExecutor;
import io.haifa.agent.runtime.core.retry.RetryListener;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import io.haifa.agent.runtime.core.trace.PromptDiagnosticsSink;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import io.haifa.agent.runtime.core.trace.TracePort;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Persisted, guarded and resumable observe-decide-act Agent loop. */
public final class DefaultAgentLoop implements AgentLoop {
    private final RunControlRegistry controls;
    private final List<AgentLoopGuard> guards;
    private final RuntimeContextBuilder contextBuilder;
    private final FrozenModelInvoker models;
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
    private final PromptDiagnosticsSink promptDiagnostics;
    private final RuntimeStateReconciler reconciler;
    private final AgentRuntimeMiddlewareChain middleware;
    private final RunInputApplier runInputs;

    public DefaultAgentLoop(
            RunControlRegistry controls,
            List<AgentLoopGuard> guards,
            RuntimeContextBuilder contextBuilder,
            FrozenModelInvoker models,
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
            PromptDiagnosticsSink promptDiagnostics,
            RuntimeStateReconciler reconciler,
            AgentRuntimeMiddlewareChain middleware,
            RunInputApplier runInputs) {
        this.controls = Objects.requireNonNull(controls);
        this.guards = List.copyOf(guards);
        this.contextBuilder = Objects.requireNonNull(contextBuilder);
        this.models = Objects.requireNonNull(models);
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
        this.promptDiagnostics = Objects.requireNonNull(promptDiagnostics);
        this.reconciler = Objects.requireNonNull(reconciler);
        this.middleware = Objects.requireNonNull(middleware);
        this.runInputs = Objects.requireNonNull(runInputs);
    }

    @Override
    public AgentLoopResult run(AgentRun run, AgentRunExecutionAttempt attempt) {
        var restored = checkpoints.restoreLatest(run);
        AgentLoopContext progress = restored.map(value -> new AgentLoopContext(
                        value.nextIteration(), value.decisionFingerprints(), value.forcedContextRebuildAttempts()))
                .orElseGet(() -> new AgentLoopContext(1, List.of()));
        progress.restoreRepairAttempts((int) state.messages(run.id()).stream()
                .filter(message -> Boolean.TRUE.equals(message.metadata().get("completionRepair")))
                .count());
        RunBudgetSnapshot initialBudget =
                RunBudgetSnapshot.from(run, progress.iteration(), 0, progress.repairAttempts(), time.now());
        progress.rebuildControlState(
                state.toolCalls(run.id()),
                state.plan(run.id()),
                run.usage().childRuns(),
                restored.isPresent(),
                initialBudget);
        decisionExecutor.applyPendingToolApproval(run);
        middleware.apply(RuntimePhase.BEFORE_RUN, new RuntimeMiddlewareContext(run, state));
        String traceId = ids.nextValue();
        while (run.status() == AgentRunStatus.RUNNING || run.status() == AgentRunStatus.SUSPENDING) {
            AgentLoopIteration iteration = new AgentLoopIteration(progress.iteration(), time.now());
            if (applyControl(run, progress, SafePoint.BEFORE_ITERATION, progress.iteration() - 1)) {
                return new AgentLoopResult(run.status(), iteration, AgentLoopDirective.STOP);
            }
            var appliedInputs = runInputs.applyPending(run, attempt, progress.iteration());
            progress.observeInteractions(appliedInputs.stream()
                            .map(input -> input.submission().inputId().value())
                            .toList())
                    .ifPresent(progressDigest -> events.append(
                            run.id(),
                            "loop.progress-observed",
                            Map.of(
                                    "iteration",
                                    progress.iteration(),
                                    "progressDigest",
                                    progressDigest,
                                    "evidence",
                                    "INTERACTION_SUPPLIED"),
                            time.now()));
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
            RuntimeLimitExceededException beforeModelLimit = modelContinuationLimit(run, progress.iteration());
            if (beforeModelLimit != null) {
                if (decisionExecutor.supportsBudgetLimitedCompletion(run)) {
                    checkpoints.capture(
                            run,
                            Math.max(0, progress.iteration() - 1),
                            progress.fingerprints(),
                            progress.forcedContextRebuildAttempts(),
                            CheckpointType.AUTOMATIC);
                    decisionExecutor.completeBudgetLimited(run, beforeModelLimit, Optional.empty());
                    return new AgentLoopResult(run.status(), iteration, AgentLoopDirective.STOP);
                }
                throw beforeModelLimit;
            }
            guards.forEach(guard -> guard.check(run, progress));
            RunBudgetSnapshot budget = RunBudgetSnapshot.from(
                    run,
                    progress.iteration(),
                    progress.failureClusterAttempts(),
                    progress.repairAttempts(),
                    time.now());
            Set<Integer> thresholds = progress.updateBudgetSnapshot(budget);
            events.append(
                    run.id(),
                    "loop.budget-snapshot",
                    Map.ofEntries(
                            Map.entry("remainingModelCalls", budget.remainingModelCalls()),
                            Map.entry("remainingToolCalls", budget.remainingToolCalls()),
                            Map.entry("remainingIterations", budget.remainingIterations()),
                            Map.entry("remainingWallTimeMillis", budget.remainingWallTimeMillis()),
                            Map.entry("remainingInputTokens", budget.remainingInputTokens()),
                            Map.entry("remainingOutputTokens", budget.remainingOutputTokens()),
                            Map.entry("failureClusterAttempts", budget.failureClusterAttempts()),
                            Map.entry("completionRepairAttempts", budget.completionRepairAttempts()),
                            Map.entry("limitingResource", budget.limitingResource()),
                            Map.entry("limitingUsed", budget.limitingUsed()),
                            Map.entry("limitingLimit", budget.limitingLimit()),
                            Map.entry("remainingPercent", budget.remainingPercent()),
                            Map.entry(
                                    "newThresholds",
                                    thresholds.stream().sorted().toList())),
                    time.now());
            if (!thresholds.isEmpty()) {
                List<Integer> orderedThresholds = thresholds.stream()
                        .sorted(java.util.Comparator.reverseOrder())
                        .toList();
                appendRuntimeControlMessage(
                        run,
                        String.join(
                                "\n",
                                "[RUNTIME_CONTROL_UPDATE]",
                                "type=BUDGET_THRESHOLD",
                                "thresholds="
                                        + orderedThresholds.stream()
                                                .map(value -> value + "%")
                                                .collect(java.util.stream.Collectors.joining("|")),
                                "nextAction=converge with authoritative evidence"),
                        Map.of(
                                "runtimeControl",
                                true,
                                "runtimeControlType",
                                "BUDGET_THRESHOLD",
                                "budgetThresholds",
                                orderedThresholds));
            }
            Optional<AgentLoopDirective> pendingTools = decisionExecutor.resumePendingTools(run, progress);
            if (pendingTools.filter(value -> value == AgentLoopDirective.WAIT).isPresent()) {
                return new AgentLoopResult(run.status(), iteration, AgentLoopDirective.WAIT);
            }
            reconciler.reconcile(run, attempt);
            recordTrace(new RuntimeTraceEvent(
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

            FrozenModelBinding model = models.bind(run);
            RuntimeContextBuildResult built = contextBuilder.build(run, progress, model);
            recordPromptDiagnostics(built);
            recordTrace(new RuntimeTraceEvent(
                    traceId,
                    run.id(),
                    java.util.Optional.of(attempt.attemptId()),
                    run.sessionId(),
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    attempt.workerId(),
                    progress.iteration(),
                    RuntimePhase.AFTER_CONTEXT_BUILD,
                    "context.built",
                    Map.<String, Object>ofEntries(
                            Map.entry(
                                    "modelConfigDigest", built.context().trace().modelConfigurationDigest()),
                            Map.entry(
                                    "runConfigurationDigest",
                                    model.configuration().reference().contentHash()),
                            Map.entry(
                                    "estimatedInputTokens",
                                    built.context().context().estimatedInputTokens()),
                            Map.entry(
                                    "selectedItems",
                                    built.context().context().items().size()),
                            Map.entry(
                                    "traceItems",
                                    built.context().trace().items().size()),
                            Map.entry(
                                    "estimatorVersion", built.context().trace().estimatorVersion()),
                            Map.entry(
                                    "selectionPolicyVersion",
                                    built.context().trace().selectionPolicyVersion()),
                            Map.entry(
                                    "compressionPolicyVersion",
                                    built.context().trace().compressionPolicyVersion()),
                            Map.entry(
                                    "compressorVersion", built.context().trace().compressorVersion()),
                            Map.entry(
                                    "forcedRebuildAttempt",
                                    built.context().trace().forcedRebuildAttempt()),
                            Map.entry("windowGeneration", built.windowIdentity()),
                            Map.entry(
                                    "compactionGeneration",
                                    built.sessionSelection().windowGeneration()),
                            Map.entry(
                                    "compactionCount", built.sessionSelection().compactionCount()),
                            Map.entry("compacted", built.sessionSelection().compacted()),
                            Map.entry(
                                    "compactionReason",
                                    built.sessionSelection().compactionReason().name()),
                            Map.entry(
                                    "compactionElapsedMillis",
                                    built.sessionSelection().compactionElapsedMillis()),
                            Map.entry(
                                    "estimatedSessionTokens",
                                    built.sessionSelection().estimatedSessionTokens()),
                            Map.entry(
                                    "sessionTokenBudget",
                                    built.sessionSelection().sessionTokenBudget()),
                            Map.entry(
                                    "summarySourceHash",
                                    built.sessionSelection()
                                            .summary()
                                            .map(io.haifa.agent.context.compression.ConversationSummary::sourceHash)
                                            .orElse("none")),
                            Map.entry(
                                    "instructionComponentDigests",
                                    built.context().trace().prompts().stream()
                                            .map(prompt -> prompt.componentId().value() + "@" + prompt.version() + ":"
                                                    + prompt.contentHash())
                                            .toList()),
                            Map.entry(
                                    "sourceIds",
                                    built.context().context().items().stream()
                                            .map(item -> item.provenance().sourceType() + ":"
                                                    + item.provenance().sourceId() + "@"
                                                    + item.provenance().sourceVersion())
                                            .toList())),
                    time.now()));
            RuntimeContextBuildResult[] builtRef = {built};
            RuntimeMiddlewareContext[] middlewareContextRef = {built.middlewareContext()};
            RuntimeMiddlewareContext middlewareContext = middlewareContextRef[0];
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
            state.appendStep(modelStep);
            AgentStep[] modelStepRef = {modelStep};
            AgentDecision decision;
            ModelInvocationResult response;
            RuntimeLimitExceededException[] budgetLimitRef = {null};
            java.util.concurrent.atomic.AtomicReference<ModelInvocationResult> invocationRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            ModelRequestId[] modelRequestIdRef = {new ModelRequestId(ids.nextValue())};
            int[] requestAttemptOffset = {0};
            try {
                response = retries.execute(
                        retryAttempt -> {
                            if (run.usage().modelCalls() >= run.budget().maxModelCalls()) {
                                throw new io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException(
                                        "modelCalls",
                                        run.budget().maxModelCalls(),
                                        run.usage().modelCalls());
                            }
                            transitions.usage(run, new AgentRunUsageDelta(0, 0, 0, 1, 0, 0, 0, 0));
                            try {
                                return models.invoke(
                                        model,
                                        run,
                                        progress.iteration(),
                                        builtRef[0].context().context(),
                                        modelRequestIdRef[0],
                                        retryAttempt - requestAttemptOffset[0]);
                            } catch (ModelInvocationException contextTooLong) {
                                if (contextTooLong.category() != ModelErrorCategory.CONTEXT_TOO_LONG
                                        || progress.forcedContextRebuildAttempts() > 0) {
                                    throw contextTooLong;
                                }
                                recordTrace(new RuntimeTraceEvent(
                                        traceId,
                                        run.id(),
                                        java.util.Optional.of(attempt.attemptId()),
                                        run.sessionId(),
                                        java.util.Optional.of(modelStepRef[0].id()),
                                        java.util.Optional.empty(),
                                        attempt.workerId(),
                                        progress.iteration(),
                                        RuntimePhase.ON_ERROR,
                                        "model.context-too-long",
                                        modelErrorAttributes(run, contextTooLong),
                                        time.now()));
                                failModelStep(modelStepRef[0], contextTooLong);
                                progress.recordForcedContextRebuild();
                                checkpoints.capture(
                                        run,
                                        Math.max(0, progress.iteration() - 1),
                                        progress.fingerprints(),
                                        progress.forcedContextRebuildAttempts(),
                                        CheckpointType.AUTOMATIC);
                                builtRef[0] = contextBuilder.build(run, progress, model);
                                middlewareContextRef[0] = builtRef[0].middlewareContext();
                                recordTrace(new RuntimeTraceEvent(
                                        traceId,
                                        run.id(),
                                        java.util.Optional.of(attempt.attemptId()),
                                        run.sessionId(),
                                        java.util.Optional.empty(),
                                        java.util.Optional.empty(),
                                        attempt.workerId(),
                                        progress.iteration(),
                                        RuntimePhase.AFTER_CONTEXT_BUILD,
                                        "context.forced-rebuild",
                                        Map.of(
                                                "modelConfigDigest",
                                                builtRef[0].context().trace().modelConfigurationDigest(),
                                                "forcedRebuildAttempt",
                                                progress.forcedContextRebuildAttempts(),
                                                "estimatedInputTokens",
                                                builtRef[0].context().context().estimatedInputTokens(),
                                                "compressionPolicyVersion",
                                                builtRef[0].context().trace().compressionPolicyVersion(),
                                                "compressorVersion",
                                                builtRef[0].context().trace().compressorVersion()),
                                        time.now()));
                                AgentStep recoveryStep = new AgentStep(
                                        new AgentStepId(ids.nextValue()),
                                        run.id(),
                                        null,
                                        null,
                                        AgentStepType.MODEL_CALL,
                                        state.steps(run.id()).size() + 1,
                                        time.now());
                                state.appendStep(recoveryStep);
                                recoveryStep.start(time.now());
                                state.appendStep(recoveryStep);
                                modelStepRef[0] = recoveryStep;
                                modelRequestIdRef[0] = new ModelRequestId(ids.nextValue());
                                requestAttemptOffset[0] = retryAttempt - 1;
                                if (run.usage().modelCalls() >= run.budget().maxModelCalls()) {
                                    throw new io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException(
                                            "modelCalls",
                                            run.budget().maxModelCalls(),
                                            run.usage().modelCalls());
                                }
                                transitions.usage(run, new AgentRunUsageDelta(0, 0, 0, 1, 0, 0, 0, 0));
                                return models.invoke(
                                        model,
                                        run,
                                        progress.iteration(),
                                        builtRef[0].context().context(),
                                        modelRequestIdRef[0],
                                        1);
                            }
                        },
                        modelRetryPolicy.policy(),
                        (failedAttempt, failure) -> modelRetryDelay(run, failedAttempt, failure),
                        () -> checkModelRetryControl(run),
                        modelRetryListener(run, modelRequestIdRef, requestAttemptOffset));
                invocationRef.set(response);
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
                recordTrace(new RuntimeTraceEvent(
                        traceId,
                        run.id(),
                        java.util.Optional.of(attempt.attemptId()),
                        run.sessionId(),
                        java.util.Optional.of(modelStepRef[0].id()),
                        java.util.Optional.empty(),
                        attempt.workerId(),
                        progress.iteration(),
                        RuntimePhase.AFTER_MODEL_CALL,
                        "model.invoke",
                        modelTraceAttributes(run, response),
                        time.now()));
                middleware.apply(RuntimePhase.AFTER_MODEL_CALL, middlewareContextRef[0]);
                decision = response.decision();
                validator.validate(run, decision);
                budgetLimitRef[0] = decisionBudgetLimit(run, progress.iteration(), decision);
                if (budgetLimitRef[0] != null && !decisionExecutor.supportsBudgetLimitedCompletion(run)) {
                    throw budgetLimitRef[0];
                }
            } catch (CancellationObservedException cancelled) {
                AgentStep cancelledModelStep = modelStepRef[0];
                if (cancelledModelStep != null
                        && cancelledModelStep.status() == io.haifa.agent.core.step.AgentStepStatus.RUNNING) {
                    cancelledModelStep.cancel(time.now());
                    state.appendStep(cancelledModelStep);
                }
                throw cancelled;
            } catch (RuntimeException error) {
                RuntimeException terminal = isContextTooLong(error) && progress.forcedContextRebuildAttempts() > 0
                        ? new ContextRebuildExhaustedException(
                                "model context remained too long after the single forced rebuild")
                        : error;
                recordTrace(new RuntimeTraceEvent(
                        traceId,
                        run.id(),
                        java.util.Optional.of(attempt.attemptId()),
                        run.sessionId(),
                        java.util.Optional.of(modelStepRef[0].id()),
                        java.util.Optional.empty(),
                        attempt.workerId(),
                        progress.iteration(),
                        RuntimePhase.ON_ERROR,
                        "model.error",
                        modelErrorAttributes(run, error),
                        time.now()));
                AgentError classified = failModelStep(modelStepRef[0], terminal);
                if (invocationRef.get() != null) {
                    models.failed(run, invocationRef.get(), progress.iteration());
                }
                middleware.apply(RuntimePhase.ON_ERROR, middlewareContextRef[0]);
                throw classified == null ? terminal : new AgentExecutionFailureException(classified, terminal);
            }
            String fingerprint = DecisionFingerprint.of(decision);
            progress.record(fingerprint);
            Map<String, Object> stepMetadata = new LinkedHashMap<>(modelTraceAttributes(run, response));
            stepMetadata.put("fingerprint", fingerprint);
            modelStepRef[0].complete(
                    new AgentStepResult(
                            "Model decision: " + decision.getClass().getSimpleName(), stepMetadata, List.of()),
                    time.now());
            state.appendStep(modelStepRef[0]);

            if (applyControl(run, progress, SafePoint.AFTER_MODEL_CALL, progress.iteration() - 1)) {
                models.failed(run, response, progress.iteration());
                return new AgentLoopResult(run.status(), iteration, AgentLoopDirective.STOP);
            }

            if (budgetLimitRef[0] != null) {
                middleware.apply(RuntimePhase.BEFORE_COMPLETION, middlewareContextRef[0]);
                checkpoints.capture(
                        run,
                        progress.iteration(),
                        progress.fingerprints(),
                        progress.forcedContextRebuildAttempts(),
                        CheckpointType.AUTOMATIC);
                Optional<FinalAnswerDecision> finalDecision =
                        decision instanceof FinalAnswerDecision value ? Optional.of(value) : Optional.empty();
                decisionExecutor.completeBudgetLimited(run, budgetLimitRef[0], finalDecision);
                models.committed(run, response, progress.iteration());
                middleware.apply(RuntimePhase.AFTER_COMPLETION, middlewareContextRef[0]);
                events.append(
                        run.id(),
                        "loop.iteration-persisted",
                        Map.of("iteration", progress.iteration(), "directive", AgentLoopDirective.STOP.name()),
                        time.now());
                return new AgentLoopResult(run.status(), iteration, AgentLoopDirective.STOP);
            }

            if (decision instanceof FinalAnswerDecision) {
                middleware.apply(RuntimePhase.BEFORE_COMPLETION, middlewareContextRef[0]);
                checkpoints.capture(
                        run,
                        progress.iteration(),
                        progress.fingerprints(),
                        progress.forcedContextRebuildAttempts(),
                        CheckpointType.AUTOMATIC);
            }
            middleware.apply(RuntimePhase.BEFORE_DECISION_EXECUTION, middlewareContextRef[0]);
            AgentLoopDirective directive;
            try {
                directive = decisionExecutor.executeModel(run, response, progress);
            } catch (RuntimeException error) {
                models.failed(run, response, progress.iteration());
                middleware.apply(RuntimePhase.ON_ERROR, middlewareContextRef[0]);
                throw error;
            }
            models.committed(run, response, progress.iteration());
            middleware.apply(RuntimePhase.AFTER_DECISION_EXECUTION, middlewareContextRef[0]);
            if (decision instanceof FinalAnswerDecision && run.status() == AgentRunStatus.COMPLETED) {
                middleware.apply(RuntimePhase.AFTER_COMPLETION, middlewareContextRef[0]);
            }
            events.append(
                    run.id(),
                    "loop.iteration-persisted",
                    Map.of("iteration", progress.iteration(), "directive", directive.name()),
                    time.now());
            AgentLoopContext.ControlObservation control = progress.observeAuthoritativeState(
                    state.toolCalls(run.id()), state.plan(run.id()), run.usage().childRuns());
            if (control.progressObserved()) {
                events.append(
                        run.id(),
                        "loop.progress-observed",
                        Map.of(
                                "iteration",
                                progress.iteration(),
                                "progressDigest",
                                control.progressDigest(),
                                "evidence",
                                "MEANINGFUL"),
                        time.now());
            }
            for (RecoveryController.Update update : control.recoveryUpdates()) {
                events.append(
                        run.id(),
                        "tool.failure-cluster-updated",
                        Map.of(
                                "iteration",
                                progress.iteration(),
                                "fingerprintDigest",
                                update.observation().fingerprint().digest(),
                                "failureCategory",
                                update.observation().category().name(),
                                "attempts",
                                update.attempts(),
                                "directive",
                                update.directive().name()),
                        time.now());
                if (update.attempts() >= 2) {
                    events.append(
                            run.id(),
                            "loop.stall-detected",
                            Map.of(
                                    "iteration",
                                    progress.iteration(),
                                    "fingerprintDigest",
                                    update.observation().fingerprint().digest(),
                                    "attempts",
                                    update.attempts()),
                            time.now());
                }
                if (update.directive() != RecoveryDirective.CONTINUE_WITH_DIAGNOSTIC) {
                    events.append(
                            run.id(),
                            "tool.recovery-strategy-required",
                            Map.of(
                                    "iteration",
                                    progress.iteration(),
                                    "fingerprintDigest",
                                    update.observation().fingerprint().digest(),
                                    "attempts",
                                    update.attempts(),
                                    "directive",
                                    update.directive().name()),
                            time.now());
                }
                if (!update.directive().terminal()) {
                    appendRuntimeControlMessage(
                            run,
                            String.join(
                                    "\n",
                                    "[RUNTIME_CONTROL_UPDATE]",
                                    "type=RECOVERY_STRATEGY",
                                    "failureCategory="
                                            + update.observation().category().name(),
                                    "attempts=" + update.attempts(),
                                    "directive=" + update.directive().name(),
                                    "nextAction=" + update.directive().guidance()),
                            Map.of(
                                    "runtimeControl", true,
                                    "runtimeControlType", "RECOVERY_STRATEGY",
                                    "failureCategory",
                                            update.observation().category().name(),
                                    "recoveryAttempts", update.attempts(),
                                    "recoveryDirective", update.directive().name()));
                }
                if (update.directive().terminal()) {
                    return structuredTermination(run, iteration, update);
                }
            }
            progress.recordProgress(control.progressDigest());
            checkpoints.capture(
                    run,
                    progress.iteration(),
                    progress.fingerprints(),
                    progress.forcedContextRebuildAttempts(),
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
            checkpoints.capture(
                    run,
                    Math.max(0, completedIteration),
                    progress.fingerprints(),
                    progress.forcedContextRebuildAttempts(),
                    CheckpointType.MANUAL);
            transitions.suspended(run);
            controls.clear(run.id());
            return true;
        }
        return false;
    }

    private AgentError failModelStep(AgentStep step, RuntimeException error) {
        if (step.status() != io.haifa.agent.core.step.AgentStepStatus.RUNNING) return null;
        AgentError classified =
                new AgentError(modelErrorCode(error), modelErrorDetails(error), ids.nextValue(), time.now());
        step.fail(new AgentStepError(classified), time.now());
        state.appendStep(step);
        return classified;
    }

    private void recordTrace(RuntimeTraceEvent event) {
        try {
            trace.record(event);
        } catch (RuntimeException ignored) {
            // Trace is a best-effort projection and never changes Agent execution semantics.
        }
    }

    private void recordPromptDiagnostics(RuntimeContextBuildResult built) {
        try {
            promptDiagnostics.record(
                    built.context().trace(),
                    built.context().context().prompts().stream()
                            .map(io.haifa.agent.context.prompt.PromptComponent::id)
                            .toList(),
                    built.context().context().items().stream()
                            .map(io.haifa.agent.context.item.ContextItem::id)
                            .toList());
        } catch (RuntimeException ignored) {
            // Diagnostics are a best-effort projection and never change Agent execution semantics.
        }
    }

    private void appendRuntimeControlMessage(AgentRun run, String text, Map<String, Object> metadata) {
        state.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId(ids.nextValue()),
                run.sessionId(),
                Optional.of(run.id()),
                Optional.empty(),
                MessageRole.RUNTIME,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new TextPart(text, "plain")),
                metadata,
                time.now()));
    }

    private static AgentErrorCode modelErrorCode(RuntimeException error) {
        if (error instanceof RuntimeLimitExceededException) return AgentErrorCode.RUN_BUDGET_EXCEEDED;
        if (error instanceof ContextRebuildExhaustedException) return AgentErrorCode.MODEL_CONTEXT_TOO_LONG;
        if (!(error instanceof ModelInvocationException modelError)) return AgentErrorCode.MODEL_CALL_FAILED;
        if ("structured_output_unsupported".equals(modelError.providerCode())) {
            return AgentErrorCode.MODEL_STRUCTURED_OUTPUT_UNSUPPORTED;
        }
        if ("structured_output_invalid".equals(modelError.providerCode())) {
            return AgentErrorCode.MODEL_STRUCTURED_OUTPUT_INVALID;
        }
        if ("output_truncated".equals(modelError.providerCode())) {
            return AgentErrorCode.MODEL_OUTPUT_TRUNCATED;
        }
        return switch (modelError.category()) {
            case AUTHENTICATION_FAILED -> AgentErrorCode.MODEL_AUTHENTICATION_FAILED;
            case PERMISSION_DENIED -> AgentErrorCode.MODEL_PERMISSION_DENIED;
            case RATE_LIMITED -> AgentErrorCode.MODEL_RATE_LIMITED;
            case TIMEOUT -> AgentErrorCode.MODEL_TIMEOUT;
            case PROVIDER_UNAVAILABLE, SERVER_ERROR, TRANSPORT_ERROR -> AgentErrorCode.MODEL_PROVIDER_UNAVAILABLE;
            case INVALID_REQUEST -> AgentErrorCode.MODEL_REQUEST_INVALID;
            case MODEL_NOT_FOUND -> AgentErrorCode.MODEL_NOT_FOUND;
            case CONTEXT_TOO_LONG -> AgentErrorCode.MODEL_CONTEXT_TOO_LONG;
            case CONTENT_REJECTED -> AgentErrorCode.MODEL_CONTENT_REJECTED;
            case EMPTY_RESPONSE, PARTIAL_RESPONSE, MALFORMED_RESPONSE -> AgentErrorCode.MODEL_RESPONSE_INVALID;
            case CANCELLED -> AgentErrorCode.MODEL_CANCELLED;
            case UNKNOWN_PROVIDER_ERROR -> AgentErrorCode.MODEL_CALL_FAILED;
        };
    }

    private static RuntimeLimitExceededException modelContinuationLimit(AgentRun run, int iteration) {
        if (run.budget().isExceededBy(run.usage())) return RuntimeLimitExceededException.forRunBudget(run);
        if (run.usage().modelCalls() >= run.budget().maxModelCalls()) {
            return new RuntimeLimitExceededException(
                    "modelCalls", run.budget().maxModelCalls(), run.usage().modelCalls());
        }
        if (iteration > run.limits().maxIterations()) {
            return new RuntimeLimitExceededException(
                    "iterations", run.limits().maxIterations(), Math.max(0, iteration - 1L));
        }
        if (run.budget().maxInputTokens() > 0
                && run.usage().inputTokens() >= run.budget().maxInputTokens()) {
            return new RuntimeLimitExceededException(
                    "inputTokens", run.budget().maxInputTokens(), run.usage().inputTokens());
        }
        if (run.budget().maxOutputTokens() > 0
                && run.usage().outputTokens() >= run.budget().maxOutputTokens()) {
            return new RuntimeLimitExceededException(
                    "outputTokens", run.budget().maxOutputTokens(), run.usage().outputTokens());
        }
        if (run.budget().maxCostMinorUnits() > 0
                && run.usage().costMinorUnits() >= run.budget().maxCostMinorUnits()) {
            return new RuntimeLimitExceededException(
                    "costMinorUnits",
                    run.budget().maxCostMinorUnits(),
                    run.usage().costMinorUnits());
        }
        return null;
    }

    private static RuntimeLimitExceededException decisionBudgetLimit(
            AgentRun run, int iteration, AgentDecision decision) {
        if (run.budget().isExceededBy(run.usage())) return RuntimeLimitExceededException.forRunBudget(run);
        if (decision instanceof FinalAnswerDecision) return null;
        if (decision instanceof ToolCallDecision toolDecision) {
            long projectedToolCalls =
                    run.usage().toolCalls() + toolDecision.requests().size();
            if (projectedToolCalls > run.budget().maxToolCalls()) {
                return new RuntimeLimitExceededException(
                        "toolCalls", run.budget().maxToolCalls(), projectedToolCalls);
            }
        }
        if (decision instanceof DelegationDecision) {
            long projectedChildRuns = run.usage().childRuns() + 1;
            if (projectedChildRuns > run.budget().maxChildRuns()) {
                return new RuntimeLimitExceededException(
                        "childRuns", run.budget().maxChildRuns(), projectedChildRuns);
            }
        }
        if (run.usage().modelCalls() >= run.budget().maxModelCalls()) {
            return new RuntimeLimitExceededException(
                    "modelCalls", run.budget().maxModelCalls(), run.usage().modelCalls());
        }
        if (iteration >= run.limits().maxIterations()) {
            return new RuntimeLimitExceededException("iterations", run.limits().maxIterations(), iteration);
        }
        return null;
    }

    private Duration modelRetryDelay(AgentRun run, int failedAttempt, RuntimeException failure) {
        Duration selected = modelRetryPolicy.delay(failedAttempt, failure);
        long elapsed = Math.max(0, Duration.between(run.createdAt(), time.now()).toMillis());
        long remaining = Math.max(0, run.limits().maxWallTimeMillis() - elapsed);
        Duration remainingWindow = Duration.ofMillis(remaining);
        return selected.compareTo(remainingWindow) <= 0 ? selected : remainingWindow;
    }

    private void checkModelRetryControl(AgentRun run) {
        if (controls.signal(run.id()) == RunControlSignal.CANCEL) throw new CancellationObservedException();
        long elapsed = Math.max(0, Duration.between(run.createdAt(), time.now()).toMillis());
        if (elapsed >= run.limits().maxWallTimeMillis()) {
            throw new RuntimeLimitExceededException(
                    "wallTimeMillis", run.limits().maxWallTimeMillis(), elapsed);
        }
    }

    private RetryListener modelRetryListener(AgentRun run, ModelRequestId[] requestIds, int[] requestAttemptOffsets) {
        return new RetryListener() {
            @Override
            public void retryScheduled(int failedAttempt, RuntimeException failure, Duration delay) {
                append("model.attempt.retry-scheduled", "RETRY_SCHEDULED", failedAttempt, failure, delay);
            }

            @Override
            public void exhausted(int finalAttempt, RuntimeException failure) {
                append("model.attempt.exhausted", "EXHAUSTED", finalAttempt, failure, Duration.ZERO);
            }

            private void append(
                    String eventType,
                    String status,
                    int retryExecutorAttempt,
                    RuntimeException failure,
                    Duration delay) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("modelRequestId", requestIds[0].value());
                data.put("status", status);
                data.put("attempt", Math.max(1, retryExecutorAttempt - requestAttemptOffsets[0]));
                data.put("delayMillis", safeDurationMillis(delay));
                if (failure instanceof ModelInvocationException modelFailure) {
                    data.put("modelCallId", modelFailure.callId().value());
                    data.put("category", modelFailure.category().name());
                    data.put("providerCode", modelFailure.providerCode());
                    data.put("retryable", modelFailure.retryable());
                    data.put("outputObserved", modelFailure.outputObserved());
                } else {
                    data.put("category", failure.getClass().getSimpleName());
                }
                events.append(run.id(), eventType, data, time.now());
            }
        };
    }

    private static long safeDurationMillis(Duration duration) {
        try {
            return duration.toMillis();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static Map<String, Object> modelErrorDetails(RuntimeException error) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (error instanceof RuntimeLimitExceededException limit) {
            details.put("resource", limit.resource());
            details.put("limit", limit.limit());
            details.put("used", limit.used());
        }
        if (error instanceof ModelInvocationException modelError) {
            details.put("modelCallId", modelError.callId().value());
            details.put("modelCategory", modelError.category().name());
            if (modelError.httpStatus() > 0) details.put("httpStatus", modelError.httpStatus());
            if (!modelError.providerCode().isBlank()) details.put("providerCode", modelError.providerCode());
            details.put("outputObserved", modelError.outputObserved());
            modelError.retryAfterMillis().ifPresent(delay -> details.put("retryAfterMillis", delay));
            details.put("providerMessage", modelError.getMessage());
        }
        return Map.copyOf(details);
    }

    private boolean isContextTooLong(RuntimeException error) {
        return error instanceof ModelInvocationException modelError
                && modelError.category() == ModelErrorCategory.CONTEXT_TOO_LONG;
    }

    private Map<String, Object> modelTraceAttributes(AgentRun run, ModelInvocationResult response) {
        Map<String, Object> attributes = modelSnapshotAttributes(run);
        attributes.putAll(response.metadata());
        attributes.put("inputTokens", response.inputTokens());
        attributes.put("outputTokens", response.outputTokens());
        attributes.put("costKnown", response.costKnown());
        attributes.put("costMinorUnits", response.costMinorUnits());
        return Map.copyOf(attributes);
    }

    private Map<String, Object> modelErrorAttributes(AgentRun run, RuntimeException error) {
        Map<String, Object> attributes = modelSnapshotAttributes(run);
        attributes.put("exceptionType", error.getClass().getSimpleName());
        if (error instanceof ModelInvocationException modelError) {
            attributes.put("category", modelError.category().name());
            attributes.put("retryable", modelError.retryable());
            attributes.put("httpStatus", modelError.httpStatus());
            attributes.put("providerCode", modelError.providerCode());
            attributes.put("modelCallId", modelError.callId().value());
            attributes.put("outputObserved", modelError.outputObserved());
            modelError.retryAfterMillis().ifPresent(delay -> attributes.put("retryAfterMillis", delay));
            attributes.put("providerMessage", modelError.getMessage());
        }
        return Map.copyOf(attributes);
    }

    private Map<String, Object> modelSnapshotAttributes(AgentRun run) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        state.configuration(run.configurationSnapshot()).ifPresent(configuration -> {
            var model = configuration.model();
            attributes.put("providerId", model.providerId().value());
            attributes.put("modelId", model.modelId().value());
            attributes.put("adapterType", model.adapterType());
            attributes.put("adapterVersion", model.adapterVersion());
            attributes.put("modelConfigDigest", model.configurationDigest());
        });
        return attributes;
    }

    private AgentLoopResult structuredTermination(
            AgentRun run, AgentLoopIteration iteration, RecoveryController.Update update) {
        RecoveryDirective directive = update.directive();
        events.append(
                run.id(),
                "run.structured-termination",
                Map.of(
                        "reason",
                        directive.name(),
                        "fingerprintDigest",
                        update.observation().fingerprint().digest(),
                        "failureCategory",
                        update.observation().category().name(),
                        "attempts",
                        update.attempts()),
                time.now());
        if (directive == RecoveryDirective.TERMINATE_CANCELLED) {
            transitions.cancelled(
                    run, new RunTerminationReason("TOOL_CANCELLED", "Tool cancellation ended the current run"));
        } else {
            AgentErrorCode code = directive == RecoveryDirective.TERMINATE_OUTCOME_UNKNOWN
                    ? AgentErrorCode.TOOL_OUTCOME_UNKNOWN
                    : AgentErrorCode.REPEATED_TOOL_FAILURE;
            AgentError error = new AgentError(
                    code,
                    Map.of(
                            "failureCategory",
                            update.observation().category().name(),
                            "fingerprintDigest",
                            update.observation().fingerprint().digest(),
                            "attempts",
                            update.attempts()),
                    ids.nextValue(),
                    time.now());
            String summary = TerminalFailureSummary.create(error, state.toolCalls(run.id()), state.steps(run.id()));
            decisionExecutor.failWithSummary(run, error, summary);
        }
        return new AgentLoopResult(run.status(), iteration, AgentLoopDirective.STOP);
    }
}
