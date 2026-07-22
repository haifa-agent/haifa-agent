package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCategory;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.error.AgentErrorSeverity;
import io.haifa.agent.core.error.Retryability;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunUsageDelta;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolExecutionError;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.credential.api.CredentialBindingScope;
import io.haifa.agent.credential.api.CredentialBroker;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.credential.api.CredentialRequest;
import io.haifa.agent.credential.api.CredentialScopeKind;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.control.RunControlSignal;
import io.haifa.agent.runtime.core.decision.ToolRequest;
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
    private final ToolPolicy policy;
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

    public ToolPipeline(
            ToolInvoker invoker,
            ToolSchemaValidator schemaValidator,
            CapabilityAuthorizer capabilityAuthorizer,
            ToolPolicy policy,
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

    public ToolPipelineOutcome execute(AgentRun run, AgentStepId stepId, ToolRequest request) {
        ToolCall call = prepare(run, stepId, request);
        return execute(run, call, request);
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
        return call;
    }

    public ToolPipelineOutcome execute(AgentRun run, ToolCall call, ToolRequest request) {
        if (call.result().isPresent())
            return new ToolPipelineOutcome.Completed(call.result().orElseThrow());
        var completed = journal.completed(run.id(), request.idempotencyKey());
        if (completed.isPresent()) return new ToolPipelineOutcome.Completed(completed.orElseThrow());
        var pending = journal.pendingResult(run.id(), request.idempotencyKey());
        if (pending.isPresent()) {
            return new ToolPipelineOutcome.Completed(persistResult(run, call, request, pending.orElseThrow()));
        }
        return executeNew(run, call, request);
    }

    private ToolPipelineOutcome executeNew(AgentRun run, ToolCall call, ToolRequest request) {
        checkCancellation(run);
        FrozenToolBinding binding = binding(run, request);
        var definition = binding.definition();
        if (call.status() != io.haifa.agent.core.tool.ToolCallStatus.APPROVED) {
            call.beginValidation();
            if (!capabilityAuthorizer.isAllowed(run, binding)) {
                call.cancel(time.now());
                throw new SecurityException(
                        "tool capability is not allowed: " + definition.name().value());
            }
            ToolSchemaValidationResult inputValidation = schemaValidator.validate(
                    definition.inputSchema(), request.arguments().values());
            if (!inputValidation.valid()) {
                call.cancel(time.now());
                throw new IllegalArgumentException("tool input failed schema validation: " + inputValidation.errors());
            }
            call.beginPolicyCheck();
            ToolPolicyDecision policyDecision = policy.evaluate(run, binding, request);
            if (policyDecision == ToolPolicyDecision.DENY) {
                call.deny(time.now());
                throw new SecurityException(
                        "tool policy denied: " + definition.name().value());
            }
            if (policyDecision == ToolPolicyDecision.REQUIRE_APPROVAL
                    || policyDecision == ToolPolicyDecision.REQUIRE_REAUTHENTICATION) {
                call.waitForApproval();
                return new ToolPipelineOutcome.ApprovalRequired(
                        binding,
                        argumentsDigest(request),
                        policyDecision == ToolPolicyDecision.REQUIRE_REAUTHENTICATION);
            }
        }
        journal.recordIntent(run.id(), request.idempotencyKey());
        call.start(time.now());
        trace.record(new RuntimeTraceEvent(
                ids.nextValue(),
                run.id(),
                java.util.Optional.empty(),
                run.sessionId(),
                java.util.Optional.of(call.stepId()),
                java.util.Optional.of(call.id()),
                java.util.Optional.empty(),
                0,
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
                            throw new RuntimeLimitExceededException("tool call budget exhausted");
                        }
                        transitions.usage(run, new AgentRunUsageDelta(0, 0, 0, 0, 1, 0, 0, 0));
                        return invokeProvider(run, call, request, binding);
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
            return new ToolPipelineOutcome.Completed(persistResult(run, call, request, rawResult));
        } catch (CancellationObservedException cancelled) {
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
            if (journalState == ToolJournalState.ACKNOWLEDGED || journalState == ToolJournalState.PENDING_RESULT) {
                journal.recordFailed(run.id(), request.idempotencyKey());
            } else if (journalState == ToolJournalState.DISPATCHED) {
                journal.recordUncertain(run.id(), request.idempotencyKey());
            } else {
                journal.recordFailed(run.id(), request.idempotencyKey());
            }
            throw exception;
        }
    }

    private ToolResult invokeProvider(AgentRun run, ToolCall call, ToolRequest request, FrozenToolBinding binding) {
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
        try {
            StringBuilder canonical = new StringBuilder();
            appendCanonical(canonical, request.arguments().schemaId());
            appendCanonical(canonical, request.arguments().schemaVersion());
            appendCanonical(canonical, request.arguments().values());
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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

    private static void appendCanonical(StringBuilder target, Object value) {
        if (value == null) {
            target.append('n');
        } else if (value instanceof String text) {
            target.append('s').append(text.length()).append(':').append(text);
        } else if (value instanceof Boolean bool) {
            target.append(bool ? "b1" : "b0");
        } else if (value instanceof Number number) {
            target.append('d')
                    .append(new java.math.BigDecimal(number.toString())
                            .stripTrailingZeros()
                            .toPlainString())
                    .append(';');
        } else if (value instanceof Map<?, ?> map) {
            target.append("m{");
            map.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> {
                        appendCanonical(target, String.valueOf(entry.getKey()));
                        appendCanonical(target, entry.getValue());
                    });
            target.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            target.append("l[");
            iterable.forEach(element -> appendCanonical(target, element));
            target.append(']');
        } else {
            throw new IllegalArgumentException("tool arguments contain a non-JSON value");
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

    private ToolResult persistResult(AgentRun run, ToolCall call, ToolRequest request, ToolResult rawResult) {
        FrozenToolBinding binding = binding(run, request);
        var definition = binding.definition();
        ToolResult result = resultNormalizer.normalize(binding, rawResult);
        if (largeResultPolicy.requiresExternalization(rawResult)) {
            var reference = putResultAssetWithOnePersistenceRetry(call, rawResult);
            var assets = new ArrayList<>(result.assets());
            if (!assets.contains(reference)) assets.add(reference);
            result = new ToolResult(
                    result.successful(), result.summary(), result.structuredData(), assets, result.artifacts(), true);
        }
        if (result.successful()) {
            call.complete(result, time.now());
        } else {
            call.fail(
                    new ToolExecutionError(new AgentError(
                            new AgentErrorCode("TOOL_BUSINESS_FAILURE"),
                            AgentErrorCategory.TOOL,
                            AgentErrorSeverity.WARNING,
                            Retryability.NOT_RETRYABLE,
                            result.summary(),
                            null,
                            java.util.Map.of("tool", definition.name().value()),
                            time.now())),
                    time.now());
        }
        journal.recordCompleted(run.id(), request.idempotencyKey(), result);
        events.append(
                run.id(),
                result.successful() ? "tool.completed" : "tool.business-failed",
                java.util.Map.of(
                        "toolCallId", call.id().value(),
                        "toolName", definition.name().value(),
                        "providerId", binding.coordinate().providerId().value(),
                        "definitionHash", binding.coordinate().definitionHash().value()),
                time.now());
        trace.record(new RuntimeTraceEvent(
                ids.nextValue(),
                run.id(),
                java.util.Optional.empty(),
                run.sessionId(),
                java.util.Optional.of(call.stepId()),
                java.util.Optional.of(call.id()),
                java.util.Optional.empty(),
                0,
                RuntimePhase.AFTER_DECISION_EXECUTION,
                "tool.persisted",
                java.util.Map.of(
                        "successful", result.successful(),
                        "truncated", result.truncated(),
                        "externalized", largeResultPolicy.requiresExternalization(rawResult)),
                time.now()));
        return result;
    }

    private io.haifa.agent.core.reference.AssetRef putResultAssetWithOnePersistenceRetry(
            ToolCall call, ToolResult rawResult) {
        try {
            return resultAssets.put(call.id(), rawResult);
        } catch (RuntimeException firstFailure) {
            return resultAssets.put(call.id(), rawResult);
        }
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
