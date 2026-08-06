package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunUsageDelta;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.core.tool.ToolExecutionError;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.credential.api.CredentialBindingScope;
import io.haifa.agent.credential.api.CredentialBroker;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.credential.api.CredentialRequest;
import io.haifa.agent.credential.api.CredentialScopeKind;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.control.RunControlSignal;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.execution.AgentExecutionFailureException;
import io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.retry.RetryExecutor;
import io.haifa.agent.runtime.core.retry.ToolRetryPolicy;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import io.haifa.agent.runtime.core.trace.TracePort;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolCancellation;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolInvoker;
import io.haifa.agent.tool.api.ToolSchemaValidationResult;
import io.haifa.agent.tool.api.ToolSchemaValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Sequential validate-authorize-policy-approve-execute-persist tool pipeline. */
public final class ToolPipeline {
    private final ToolInvoker invoker;
    private final ToolSchemaValidator schemaValidator;
    private final CapabilityAuthorizer capabilityAuthorizer;
    private final PublicToolPolicy policy;
    private final CredentialBroker credentials;
    private final ToolExecutionJournal journal;
    private final RuntimeStateRepository state;
    private final IdentifierGenerator ids;
    private final TimeProvider time;
    private final RuntimeEventAppender events;
    private final RunControlRegistry controls;
    private final ToolExecutionEnvironment environment;
    private final ToolResultNormalizer resultNormalizer;
    private final RetryExecutor retries;
    private final ToolRetryPolicy retryPolicy;
    private final TracePort trace;
    private final RunTransitionCoordinator transitions;
    private final ToolResultAssetStore resultAssets;
    private final LargeToolResultPolicy largeResultPolicy;
    private final FrozenToolBindingResolver bindings = new FrozenToolBindingResolver();
    private final java.util.concurrent.ConcurrentHashMap<io.haifa.agent.core.tool.ToolCallId, PolicyDecision>
            approvedDecisions = new java.util.concurrent.ConcurrentHashMap<>();

    public ToolPipeline(
            ToolInvoker invoker,
            ToolSchemaValidator schemaValidator,
            CapabilityAuthorizer capabilityAuthorizer,
            PublicToolPolicy policy,
            CredentialBroker credentials,
            ToolExecutionJournal journal,
            RuntimeStateRepository state,
            IdentifierGenerator ids,
            TimeProvider time,
            RuntimeEventAppender events,
            RunControlRegistry controls,
            ToolExecutionEnvironment environment,
            ToolResultNormalizer resultNormalizer,
            RetryExecutor retries,
            ToolRetryPolicy retryPolicy,
            TracePort trace,
            RunTransitionCoordinator transitions,
            ToolResultAssetStore resultAssets,
            LargeToolResultPolicy largeResultPolicy) {
        this.invoker = Objects.requireNonNull(invoker);
        this.schemaValidator = Objects.requireNonNull(schemaValidator);
        this.capabilityAuthorizer = Objects.requireNonNull(capabilityAuthorizer);
        this.policy = Objects.requireNonNull(policy);
        this.credentials = credentials;
        this.journal = Objects.requireNonNull(journal);
        this.state = Objects.requireNonNull(state);
        this.ids = Objects.requireNonNull(ids);
        this.time = Objects.requireNonNull(time);
        this.events = Objects.requireNonNull(events);
        this.controls = Objects.requireNonNull(controls);
        this.environment = Objects.requireNonNull(environment);
        this.resultNormalizer = Objects.requireNonNull(resultNormalizer);
        this.retries = Objects.requireNonNull(retries);
        this.retryPolicy = Objects.requireNonNull(retryPolicy);
        this.trace = Objects.requireNonNull(trace);
        this.transitions = Objects.requireNonNull(transitions);
        this.resultAssets = Objects.requireNonNull(resultAssets);
        this.largeResultPolicy = Objects.requireNonNull(largeResultPolicy);
    }

    public ToolPipelineOutcome execute(AgentRun run, AgentStepId stepId, ToolRequest request, int iteration) {
        ToolCall call = prepare(run, stepId, request);
        return execute(run, call, request, iteration);
    }

    public ToolCall prepare(AgentRun run, AgentStepId stepId, ToolRequest request) {
        ToolCall existing = state.toolCalls(run.id()).stream()
                .filter(call -> call.idempotencyKey().equals(request.idempotencyKey()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            if (!existing.providerCorrelationId().equals(request.providerCorrelationId())
                    || !existing.toolName().equals(request.toolName())
                    || !existing.toolVersion().equals(request.toolVersion())
                    || !existing.arguments().equals(request.arguments())) {
                throw new IllegalStateException("runtime idempotency key was reused for a different tool request");
            }
            return existing;
        }
        ToolCall call = new ToolCall(
                request.toolCallId(),
                run.id(),
                stepId,
                request.providerCorrelationId(),
                request.idempotencyKey(),
                request.toolName(),
                request.toolVersion(),
                request.arguments(),
                time.now());
        state.appendToolCall(call);
        appendToolEvent(run, call, "tool.requested", "REQUESTED", "NONE", "");
        return call;
    }

    public ToolPipelineOutcome execute(AgentRun run, ToolCall call, ToolRequest request, int iteration) {
        if (iteration < 1) throw new IllegalArgumentException("iteration must be positive");
        if (call.result().isPresent())
            return new ToolPipelineOutcome.Completed(call.result().orElseThrow());
        var completed = journal.completed(run.id(), request.idempotencyKey());
        if (completed.isPresent()) {
            return new ToolPipelineOutcome.Completed(
                    persistResult(run, call, request, completed.orElseThrow(), iteration));
        }
        var pending = journal.pendingResult(run.id(), request.idempotencyKey());
        if (pending.isPresent()) {
            return new ToolPipelineOutcome.Completed(
                    persistResult(run, call, request, pending.orElseThrow(), iteration));
        }
        var journalState = journal.state(run.id(), request.idempotencyKey());
        if (journalState
                .filter(value -> value == ToolJournalState.DISPATCHED || value == ToolJournalState.ACKNOWLEDGED)
                .isPresent()) {
            journal.recordUncertain(run.id(), request.idempotencyKey());
            throw outcomeUnknown(call, journalState.orElseThrow());
        }
        if (journalState
                .filter(value -> value == ToolJournalState.OUTCOME_UNKNOWN)
                .isPresent()) {
            throw outcomeUnknown(call, ToolJournalState.OUTCOME_UNKNOWN);
        }
        return executeNew(run, call, request, iteration);
    }

    private AgentExecutionFailureException outcomeUnknown(ToolCall call, ToolJournalState journalState) {
        AgentError error = new AgentError(
                AgentErrorCode.TOOL_OUTCOME_UNKNOWN,
                Map.of(
                        "toolCallId", call.id().value(),
                        "tool", call.toolName(),
                        "journalState", journalState.name(),
                        "outcomeKnown", false),
                ids.nextValue(),
                time.now());
        if (call.status() == ToolCallStatus.RUNNING) {
            call.fail(new ToolExecutionError(error), time.now());
            state.appendToolCall(call);
        }
        return new AgentExecutionFailureException(
                error, new IllegalStateException("tool outcome is unknown; automatic replay is forbidden"));
    }

    private ToolPipelineOutcome executeNew(AgentRun run, ToolCall call, ToolRequest request, int iteration) {
        checkCancellation(run);
        FrozenToolBinding binding = binding(run, request);
        var definition = binding.definition();
        boolean approved = call.status() == io.haifa.agent.core.tool.ToolCallStatus.APPROVED;
        if (!approved) {
            call.beginValidation();
        }
        if (!capabilityAuthorizer.isAllowed(run, binding)) {
            call.cancel(time.now());
            state.appendToolCall(call);
            appendToolEvent(run, call, "tool.cancelled", "CANCELLED", "CAPABILITY_DENIED", "");
            throw new SecurityException(
                    "tool capability is not allowed: " + definition.name().value());
        }
        ToolSchemaValidationResult inputValidation = schemaValidator.validate(
                definition.inputSchema(), request.arguments().values());
        if (!inputValidation.valid()) {
            call.cancel(time.now());
            state.appendToolCall(call);
            throw new ToolInputValidationException(inputValidation.errors());
        }
        if (!approved) {
            call.beginPolicyCheck();
        }
        PolicyDecision currentDecision = policy.evaluate(run, binding, request);
        if (currentDecision.effect() == PolicyEffect.DENY) {
            if (approved) call.cancel(time.now());
            else call.deny(time.now());
            state.appendToolCall(call);
            appendToolEvent(run, call, "tool.cancelled", "CANCELLED", "POLICY_DENIED", "");
            throw new SecurityException(
                    "tool policy denied: " + definition.name().value());
        }
        PolicyDecision effectiveDecision = currentDecision;
        PolicyDecision approvedDecision = approved ? approvedDecisions.remove(call.id()) : null;
        if (currentDecision.effect() == PolicyEffect.ASK) {
            if (!approved) {
                call.waitForApproval();
                state.appendToolCall(call);
                return new ToolPipelineOutcome.ApprovalRequired(
                        binding,
                        argumentsDigest(request),
                        currentDecision.challenge().orElseThrow()
                                == io.haifa.agent.policy.api.PolicyChallenge.REAUTHENTICATE,
                        currentDecision);
            }
            if (approvedDecision == null
                    || approvedDecision.effect() != PolicyEffect.ASK
                    || !approvedDecision.requestDigest().equals(currentDecision.requestDigest())
                    || !approvedDecision.challenge().equals(currentDecision.challenge())) {
                call.cancel(time.now());
                state.appendToolCall(call);
                throw new SecurityException("approved tool policy decision is missing or has drifted");
            }
            effectiveDecision = approvedDecision;
        }
        journal.recordIntent(run.id(), request.idempotencyKey());
        call.start(time.now());
        state.appendToolCall(call);
        appendToolEvent(run, call, "tool.started", "STARTED", "NONE", "");
        PolicyDecision dispatchDecision = effectiveDecision;
        recordTrace(new RuntimeTraceEvent(
                ids.nextValue(),
                run.id(),
                java.util.Optional.empty(),
                run.sessionId(),
                java.util.Optional.of(call.stepId()),
                java.util.Optional.of(call.id()),
                java.util.Optional.empty(),
                iteration,
                RuntimePhase.BEFORE_DECISION_EXECUTION,
                "tool.execute",
                java.util.Map.of(
                        "toolName", definition.name().value(),
                        "toolVersion", definition.version().value(),
                        "providerId", definition.providerId().value(),
                        "definitionHash", binding.coordinate().definitionHash().value()),
                time.now()));
        try (var permit = environment.acquire(run, binding)) {
            ToolResult rawResult = retries.execute(
                    () -> {
                        if (run.usage().toolCalls() >= run.budget().maxToolCalls()) {
                            throw new RuntimeLimitExceededException(
                                    "toolCalls",
                                    run.budget().maxToolCalls(),
                                    run.usage().toolCalls());
                        }
                        transitions.usage(run, new AgentRunUsageDelta(0, 0, 0, 0, 1, 0, 0, 0));
                        return invokeProvider(run, call, request, binding, dispatchDecision);
                    },
                    retryPolicy.forTool(binding));
            if (rawResult.successful()) {
                ToolSchemaValidationResult outputValidation =
                        schemaValidator.validate(definition.outputSchema(), rawResult.structuredData());
                if (!outputValidation.valid()) {
                    throw new IllegalStateException(
                            "tool output failed schema validation: " + outputValidation.errors());
                }
            } else {
                validateFailureEnvelope(rawResult);
            }
            journal.recordPendingResult(run.id(), request.idempotencyKey(), rawResult);
            return new ToolPipelineOutcome.Completed(persistResult(run, call, request, rawResult, iteration));
        } catch (CancellationObservedException cancelled) {
            appendToolEvent(run, call, "tool.cancelled", "CANCELLED", "RUN_CANCELLED", "");
            throw cancelled;
        } catch (RuntimeException exception) {
            if (exception instanceof io.haifa.agent.tool.api.ToolInvocationException invocationFailure) {
                if (invocationFailure.dispatchState() == io.haifa.agent.tool.api.ToolDispatchState.DISPATCHED
                        || invocationFailure.dispatchState()
                                == io.haifa.agent.tool.api.ToolDispatchState.OUTCOME_UNKNOWN) {
                    journal.recordDispatched(run.id(), request.idempotencyKey());
                } else if (invocationFailure.dispatchState()
                        == io.haifa.agent.tool.api.ToolDispatchState.ACKNOWLEDGED) {
                    journal.recordDispatched(run.id(), request.idempotencyKey());
                    journal.recordAcknowledged(run.id(), request.idempotencyKey());
                }
            }
            ToolJournalState journalState =
                    journal.state(run.id(), request.idempotencyKey()).orElse(ToolJournalState.INTENT_RECORDED);
            boolean resultPersistenceFailed = exception instanceof ToolResultPersistenceException;
            boolean uncertain =
                    journalState == ToolJournalState.ACKNOWLEDGED || journalState == ToolJournalState.DISPATCHED;
            boolean limitExceeded = exception instanceof RuntimeLimitExceededException;
            String invocationFailureCode = exception instanceof io.haifa.agent.tool.api.ToolInvocationException failure
                    ? failure.failureCode()
                    : null;
            AgentErrorCode failureCode;
            if (resultPersistenceFailed) {
                failureCode = AgentErrorCode.TOOL_RESULT_PERSISTENCE_FAILED;
            } else if (limitExceeded && !uncertain) {
                journal.recordFailed(run.id(), request.idempotencyKey());
                failureCode = AgentErrorCode.RUN_BUDGET_EXCEEDED;
                appendToolEvent(run, call, "tool.failed", "FAILED", "RUN_BUDGET_EXCEEDED", "");
            } else if (uncertain) {
                journal.recordUncertain(run.id(), request.idempotencyKey());
                failureCode = AgentErrorCode.TOOL_OUTCOME_UNKNOWN;
                appendToolEvent(run, call, "tool.failed", "FAILED", "OUTCOME_UNKNOWN", "");
            } else {
                journal.recordFailed(run.id(), request.idempotencyKey());
                failureCode = AgentErrorCode.isKnownWireCode(invocationFailureCode)
                        ? AgentErrorCode.fromWireCode(invocationFailureCode)
                        : AgentErrorCode.TOOL_INVOCATION_FAILED;
                appendToolEvent(run, call, "tool.failed", "FAILED", failureCode.wireCode(), "");
            }
            Map<String, Object> errorDetails;
            if (resultPersistenceFailed) {
                errorDetails = Map.of(
                        "tool", definition.name().value(),
                        "toolCallId", call.id().value(),
                        "journalState", journalState.name(),
                        "persistenceStage", "TOOL_RESULT",
                        "outcomeKnown", true);
            } else if (exception instanceof RuntimeLimitExceededException limitFailure) {
                errorDetails = Map.of(
                        "tool", definition.name().value(),
                        "toolCallId", call.id().value(),
                        "resource", limitFailure.resource(),
                        "limit", limitFailure.limit(),
                        "used", limitFailure.used(),
                        "journalState", journalState.name(),
                        "outcomeKnown", !uncertain);
            } else if (exception instanceof io.haifa.agent.tool.api.ToolInvocationException invocationFailure) {
                errorDetails = Map.of(
                        "tool", definition.name().value(),
                        "toolCallId", call.id().value(),
                        "journalState", journalState.name(),
                        "sideEffecting", !definition.sideEffects().isEmpty(),
                        "outcomeKnown", !uncertain,
                        "failureCode", invocationFailure.failureCode(),
                        "dispatchState", invocationFailure.dispatchState().name());
            } else {
                errorDetails = Map.of(
                        "tool", definition.name().value(),
                        "toolCallId", call.id().value(),
                        "journalState", journalState.name(),
                        "sideEffecting", !definition.sideEffects().isEmpty(),
                        "outcomeKnown", !uncertain);
            }
            AgentError failureError = new AgentError(failureCode, errorDetails, ids.nextValue(), time.now());
            if (!resultPersistenceFailed && call.status() == ToolCallStatus.RUNNING) {
                call.fail(new ToolExecutionError(failureError), time.now());
                state.appendToolCall(call);
            }
            throw new AgentExecutionFailureException(failureError, exception);
        }
    }

    private ToolResult invokeProvider(
            AgentRun run,
            ToolCall call,
            ToolRequest request,
            FrozenToolBinding binding,
            PolicyDecision effectiveDecision) {
        var definition = binding.definition();
        var now = time.now();
        var deadline = now.plus(definition.timeout());
        List<CredentialLease> leases = new ArrayList<>();
        try {
            if (!definition.credentialRequirements().isEmpty() && credentials == null) {
                throw new SecurityException("tool requires credentials but no credential broker is configured");
            }
            for (var requirement : definition.credentialRequirements()) {
                List<CredentialBindingScope> scopes = new ArrayList<>();
                scopes.add(new CredentialBindingScope(
                        CredentialScopeKind.SESSION, run.sessionId().value()));
                run.project()
                        .ifPresent(project -> scopes.add(
                                new CredentialBindingScope(CredentialScopeKind.PROJECT, project.projectId())));
                scopes.add(new CredentialBindingScope(
                        CredentialScopeKind.USER, run.principal().principalId()));
                scopes.add(new CredentialBindingScope(CredentialScopeKind.SYSTEM, "system"));
                leases.add(credentials.issue(new CredentialRequest(
                        run.tenant(),
                        run.principal(),
                        run.id(),
                        binding.coordinate().externalForm(),
                        requirement,
                        scopes,
                        java.util.Optional.empty(),
                        now,
                        deadline)));
            }
            try {
                ToolResult result = invoker.invoke(new ToolInvocationRequest(
                        binding,
                        call.id(),
                        run.id(),
                        run.tenant(),
                        run.principal(),
                        request.arguments(),
                        deadline,
                        java.util.Optional.of(request.idempotencyKey().value()),
                        java.util.Optional.of(effectiveDecision.id().value()),
                        (ToolCancellation) () -> controls.signal(run.id()) == RunControlSignal.CANCEL,
                        leases,
                        new io.haifa.agent.tool.api.ToolInvocationObserver() {
                            @Override
                            public void dispatched() {
                                journal.recordDispatched(run.id(), request.idempotencyKey());
                            }

                            @Override
                            public void acknowledged() {
                                journal.recordAcknowledged(run.id(), request.idempotencyKey());
                            }
                        }));
                return leases.isEmpty() ? result : redactResult(result, credentials.redactor());
            } catch (RuntimeException exception) {
                if (leases.isEmpty()) throw exception;
                String detail = credentials.redactor().redact(exception.getMessage());
                if (exception instanceof io.haifa.agent.tool.api.ToolInvocationException invocationFailure) {
                    throw new io.haifa.agent.tool.api.ToolInvocationException(
                            invocationFailure.failureCode(),
                            invocationFailure.dispatchState(),
                            detail == null || detail.isBlank() ? "tool provider invocation failed" : detail);
                }
                throw new IllegalStateException(
                        detail == null || detail.isBlank()
                                ? "tool provider invocation failed"
                                : "tool provider invocation failed: " + detail);
            }
        } finally {
            for (int index = leases.size() - 1; index >= 0; index--)
                leases.get(index).close();
        }
    }

    static ToolResult redactResult(ToolResult result, io.haifa.agent.credential.api.SecretRedactor redactor) {
        return new ToolResult(
                result.successful(),
                redactor.redact(result.summary()),
                redactObject(result.structuredData(), redactor),
                result.assets(),
                result.artifacts(),
                result.truncated());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> redactObject(
            Map<String, Object> value, io.haifa.agent.credential.api.SecretRedactor redactor) {
        var redacted = new java.util.LinkedHashMap<String, Object>();
        value.forEach((key, element) -> redacted.put(redactor.redact(key), redactValue(element, redactor)));
        return redacted;
    }

    private static Object redactValue(Object value, io.haifa.agent.credential.api.SecretRedactor redactor) {
        if (value instanceof String text) return redactor.redact(text);
        if (value instanceof Map<?, ?> map) return redactObject((Map<String, Object>) map, redactor);
        if (value instanceof List<?> list)
            return list.stream().map(element -> redactValue(element, redactor)).toList();
        return value;
    }

    static String argumentsDigest(ToolRequest request) {
        return io.haifa.agent.tool.api.ToolArgumentsDigest.sha256(request.arguments());
    }

    private static void validateFailureEnvelope(ToolResult result) {
        int[] budget = new int[] {0, 0};
        inspectFailureValue(result.structuredData(), 0, budget);
        if (result.summary().length() > 16_384) {
            throw new IllegalStateException("tool failure summary exceeds maximum size");
        }
    }

    private static void inspectFailureValue(Object value, int depth, int[] budget) {
        if (depth > 32 || ++budget[0] > 4096) {
            throw new IllegalStateException("tool failure envelope exceeds structural limits");
        }
        if (value instanceof String text) {
            budget[1] = Math.addExact(budget[1], text.length());
            if (budget[1] > 1_048_576) {
                throw new IllegalStateException("tool failure envelope exceeds maximum size");
            }
        } else if (value instanceof Map<?, ?> map) {
            map.forEach((key, element) -> {
                inspectFailureValue(String.valueOf(key), depth + 1, budget);
                inspectFailureValue(element, depth + 1, budget);
            });
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(element -> inspectFailureValue(element, depth + 1, budget));
        } else if (value != null && !(value instanceof Number) && !(value instanceof Boolean)) {
            throw new IllegalStateException("tool failure envelope contains a non-JSON value");
        }
    }

    public void validateApprovalTarget(AgentRun run, ToolCall call, ToolRequest request, ToolApprovalTarget target) {
        FrozenToolBinding binding = binding(run, request);
        if (!call.id().equals(target.toolCallId())
                || !binding.coordinate().externalForm().equals(target.coordinate())
                || !binding.coordinate().definitionHash().value().equals(target.definitionHash())
                || !argumentsDigest(request).equals(target.argumentsDigest())) {
            throw new SecurityException("tool approval target drifted from the frozen invocation");
        }
        String principalScope = run.tenant().tenantId() + ":" + run.principal().principalType() + ":"
                + run.principal().principalId();
        if (!principalScope.equals(target.principalScope())) {
            throw new SecurityException("tool approval principal scope changed");
        }
    }

    public void recordApprovedDecision(ToolCall call, PolicyDecision decision) {
        Objects.requireNonNull(call, "call must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        if (call.status() != ToolCallStatus.WAITING_APPROVAL) {
            throw new IllegalStateException("tool call is not waiting for approval");
        }
        approvedDecisions.put(call.id(), decision);
    }

    private ToolResult persistResult(
            AgentRun run, ToolCall call, ToolRequest request, ToolResult rawResult, int iteration) {
        FrozenToolBinding binding = binding(run, request);
        var definition = binding.definition();
        ToolResult result = resultNormalizer.normalize(binding, rawResult);
        boolean externalizationRequired = largeResultPolicy.requiresExternalization(rawResult);
        boolean externalized = false;
        if (externalizationRequired) {
            var reference = putResultAssetWithOnePersistenceRetry(call, rawResult);
            var assets = new ArrayList<>(result.assets());
            if (reference.isPresent() && !assets.contains(reference.get())) {
                assets.add(reference.get());
                externalized = true;
            }
            result = new ToolResult(
                    result.successful(), result.summary(), result.structuredData(), assets, result.artifacts(), true);
        }
        if (call.status() == ToolCallStatus.POLICY_CHECK || call.status() == ToolCallStatus.APPROVED) {
            call.start(time.now());
        }
        if (result.successful()) {
            call.complete(result, time.now());
        } else {
            call.fail(
                    new ToolExecutionError(new AgentError(
                            AgentErrorCode.TOOL_BUSINESS_FAILURE,
                            failureAttributes(definition.name().value(), result),
                            ids.nextValue(),
                            time.now())),
                    time.now());
        }
        try {
            state.appendToolCall(call);
            journal.recordCompleted(run.id(), request.idempotencyKey(), result);
            appendToolEvent(
                    run,
                    call,
                    result.successful() ? "tool.succeeded" : "tool.failed",
                    result.successful() ? "SUCCEEDED" : "FAILED",
                    result.successful() ? "NONE" : "TOOL_BUSINESS_FAILURE",
                    result.assets().isEmpty() ? "" : result.assets().getFirst().assetId());
            appendExecutionAndResourceEvents(run, call, result);
        } catch (RuntimeException persistenceFailure) {
            throw new ToolResultPersistenceException(persistenceFailure);
        }
        recordTrace(new RuntimeTraceEvent(
                ids.nextValue(),
                run.id(),
                java.util.Optional.empty(),
                run.sessionId(),
                java.util.Optional.of(call.stepId()),
                java.util.Optional.of(call.id()),
                java.util.Optional.empty(),
                iteration,
                RuntimePhase.AFTER_DECISION_EXECUTION,
                "tool.persisted",
                java.util.Map.of(
                        "successful",
                        result.successful(),
                        "truncated",
                        result.truncated(),
                        "externalizationRequired",
                        externalizationRequired,
                        "externalized",
                        externalized),
                time.now()));
        return result;
    }

    private void appendToolEvent(
            AgentRun run, ToolCall call, String type, String status, String reasonCode, String resultRef) {
        events.append(
                run.id(),
                type,
                java.util.Map.of(
                        "toolCallId",
                        call.id().value(),
                        "displayName",
                        call.toolName(),
                        "status",
                        status,
                        "reasonCode",
                        reasonCode,
                        "targetSummary",
                        targetSummary(call),
                        "resultRef",
                        resultRef),
                time.now());
    }

    static String targetSummary(ToolCall call) {
        if (!isExecutionTool(call)) return call.toolName();
        Map<String, Object> arguments = call.arguments().values();
        String mode = safeText(arguments.get("mode"), "COMMAND");
        String language = safeText(arguments.get("language"), "default-shell");
        String purpose = safeText(arguments.get("purpose"), "Approved execution");
        return boundedText(mode + " · " + language + " · " + purpose, 512);
    }

    private void appendExecutionAndResourceEvents(AgentRun run, ToolCall call, ToolResult result) {
        if (isExecutionTool(call)) {
            Map<String, Object> data = result.structuredData();
            if (Boolean.TRUE.equals(data.get("scratchProvisioned"))) {
                events.append(
                        run.id(),
                        "execution.scratch-provisioned",
                        Map.of(
                                "toolCallId",
                                call.id().value(),
                                "specDigest",
                                safeText(data.get("scratchSpecDigest"), "unknown"),
                                "capability",
                                "WRITABLE_PRIVATE_SCRATCH"),
                        time.now());
            }
            if (Boolean.TRUE.equals(data.get("scratchCleanupFailed"))) {
                events.append(
                        run.id(),
                        "execution.scratch-cleanup-failed",
                        Map.of(
                                "toolCallId",
                                call.id().value(),
                                "specDigest",
                                safeText(data.get("scratchSpecDigest"), "unknown"),
                                "status",
                                "OUTCOME_UNKNOWN"),
                        time.now());
            }
            Object status = data.get("status");
            if (status instanceof String lifecycle) {
                var event = new java.util.LinkedHashMap<String, Object>();
                event.put(
                        "executionId",
                        data.get("executionId") instanceof String id
                                ? id
                                : call.id().value());
                event.put("toolCallId", call.id().value());
                event.put("status", lifecycle);
                event.put(
                        "commandSummary",
                        safeText(call.arguments().values().get("purpose"), "approved command or script"));
                event.put("logicalWorkdir", safeText(call.arguments().values().get("workdir"), "."));
                event.put("streamKind", "MERGED");
                event.put("chunkOrRef", executionOutput(data));
                event.put("truncated", Boolean.TRUE.equals(data.get("truncated")));
                if (data.get("exitCode") instanceof Number exitCode) event.put("exitCode", exitCode.intValue());
                if (data.get("fileChangeSetId") instanceof String changeSet) {
                    event.put("fileChangeSetRef", changeSet);
                }
                events.append(
                        run.id(),
                        switch (lifecycle) {
                            case "CANCELLED" -> "execution.cancelled";
                            case "SUCCEEDED" -> "execution.completed";
                            default -> "execution.failed";
                        },
                        Map.copyOf(event),
                        time.now());
                if (data.get("fileChangeSetId") instanceof String changeSet) {
                    appendResource(run, changeSet, "workspace-change-set", "Workspace changes", "AVAILABLE");
                }
            }
        }
        result.artifacts()
                .forEach(reference ->
                        appendResource(run, reference.artifactId(), "artifact", "Published artifact", "AVAILABLE"));
    }

    private static Map<String, Object> failureAttributes(String toolName, ToolResult result) {
        var attributes = new java.util.LinkedHashMap<String, Object>();
        attributes.put("tool", toolName);
        for (String key : List.of(
                "failureCategory",
                "stableFailureCode",
                "resourceClass",
                "operationFamily",
                "sandboxProfileDigest",
                "failureCode",
                "status",
                "fileChangeSetId")) {
            Object value = result.structuredData().get(key);
            if (value instanceof String text && !text.isBlank() && text.length() <= 256) {
                attributes.put(key, text);
            }
        }
        return Map.copyOf(attributes);
    }

    private void recordTrace(RuntimeTraceEvent event) {
        try {
            trace.record(event);
        } catch (RuntimeException ignored) {
            // Trace is a best-effort projection and never changes Tool execution semantics.
        }
    }

    private static String executionOutput(Map<String, Object> data) {
        Object legacy = data.get("outputRef") != null ? data.get("outputRef") : data.get("output");
        if (legacy != null) return boundedText(legacy, 4096);
        String stdout = boundedText(data.get("stdoutSummary"), 3072);
        String stderr = boundedText(data.get("stderrSummary"), 1024);
        if (stdout.isBlank()) return stderr;
        if (stderr.isBlank()) return stdout;
        return stdout + "\n" + stderr;
    }

    private void appendResource(AgentRun run, String reference, String kind, String title, String status) {
        events.append(
                run.id(),
                kind.equals("artifact") ? "artifact.available" : "workspace.change-set.available",
                Map.of(
                        "reference", reference,
                        "kind", kind,
                        "title", title,
                        "status", status,
                        "action", "inspect"),
                time.now());
    }

    private static String safeText(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? boundedText(text, 512) : fallback;
    }

    private static String boundedText(Object value, int maximum) {
        if (!(value instanceof String text)) return "";
        StringBuilder safe = new StringBuilder(Math.min(text.length(), maximum));
        text.codePoints().forEach(codePoint -> {
            if ((codePoint == '\n' || codePoint == '\r' || codePoint == '\t' || !Character.isISOControl(codePoint))
                    && safe.length() + Character.charCount(codePoint) <= maximum) {
                safe.appendCodePoint(codePoint);
            }
        });
        return safe.toString();
    }

    private java.util.Optional<io.haifa.agent.core.reference.AssetRef> putResultAssetWithOnePersistenceRetry(
            ToolCall call, ToolResult rawResult) {
        var firstAttempt = resultAssets.tryPut(call.id(), rawResult);
        return firstAttempt.isPresent() ? firstAttempt : resultAssets.tryPut(call.id(), rawResult);
    }

    private static boolean isExecutionTool(ToolCall call) {
        return "execution.run".equals(call.toolName()) || "execution_run".equals(call.toolName());
    }

    public boolean hasUncertainExecution(AgentRun run) {
        return journal.hasUncertain(run.id());
    }

    private FrozenToolBinding binding(AgentRun run, ToolRequest request) {
        var configuration = state.configuration(run.configurationSnapshot())
                .orElseThrow(() -> new IllegalStateException("run configuration snapshot is unavailable"));
        return bindings.resolve(configuration.toolBindings(), request);
    }

    private void checkCancellation(AgentRun run) {
        if (controls.signal(run.id()) == RunControlSignal.CANCEL) throw new CancellationObservedException();
    }
}
