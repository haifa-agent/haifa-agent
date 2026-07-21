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
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolExecutionError;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.control.RunControlSignal;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.retry.RetryExecutor;
import io.haifa.agent.runtime.core.retry.ToolRetryPolicy;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import io.haifa.agent.runtime.core.trace.TracePort;
import java.util.Objects;

/** Sequential validate-authorize-policy-approve-execute-persist tool pipeline. */
public final class ToolPipeline {
    private final ToolRegistry registry;
    private final ToolSchemaValidator schemaValidator;
    private final CapabilityAuthorizer capabilityAuthorizer;
    private final ToolPolicy policy;
    private final ApprovalGateway approval;
    private final ToolExecutor executor;
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

    public ToolPipeline(
            ToolRegistry registry,
            ToolSchemaValidator schemaValidator,
            CapabilityAuthorizer capabilityAuthorizer,
            ToolPolicy policy,
            ApprovalGateway approval,
            ToolExecutor executor,
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
            RunTransitionCoordinator transitions) {
        this.registry = Objects.requireNonNull(registry);
        this.schemaValidator = Objects.requireNonNull(schemaValidator);
        this.capabilityAuthorizer = Objects.requireNonNull(capabilityAuthorizer);
        this.policy = Objects.requireNonNull(policy);
        this.approval = Objects.requireNonNull(approval);
        this.executor = Objects.requireNonNull(executor);
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
    }

    public ToolResult execute(AgentRun run, AgentStepId stepId, ToolRequest request) {
        return journal.completed(run.id(), request.idempotencyKey()).orElseGet(() -> executeNew(run, stepId, request));
    }

    private ToolResult executeNew(AgentRun run, AgentStepId stepId, ToolRequest request) {
        checkCancellation(run);
        ToolDefinition definition = registry.find(request.toolName(), request.toolVersion())
                .orElseThrow(() -> new IllegalArgumentException("unknown tool: " + request.toolName()));
        ToolCall call = new ToolCall(
                new ToolCallId(ids.nextValue()),
                run.id(),
                stepId,
                definition.name(),
                definition.version(),
                request.arguments(),
                time.now());
        state.appendToolCall(call);
        call.beginValidation();
        if (!capabilityAuthorizer.isAllowed(run, definition)) {
            call.cancel(time.now());
            throw new SecurityException("tool capability is not allowed: " + definition.name());
        }
        try {
            schemaValidator.validate(definition, request.arguments());
        } catch (IllegalArgumentException invalidArguments) {
            call.cancel(time.now());
            throw invalidArguments;
        }
        call.beginPolicyCheck();
        ToolPolicyDecision policyDecision = policy.evaluate(run, definition, request);
        if (policyDecision == ToolPolicyDecision.DENY) {
            call.deny(time.now());
            throw new SecurityException("tool policy denied: " + definition.name());
        }
        if (policyDecision == ToolPolicyDecision.REQUIRE_APPROVAL) {
            call.waitForApproval();
            if (!approval.approve(run, request)) {
                call.deny(time.now());
                throw new SecurityException("tool approval denied: " + definition.name());
            }
            call.approve();
        }
        journal.recordIntent(run.id(), request.idempotencyKey());
        call.start(time.now());
        trace.record(new RuntimeTraceEvent(
                ids.nextValue(),
                run.id(),
                java.util.Optional.empty(),
                run.sessionId(),
                java.util.Optional.of(stepId),
                java.util.Optional.of(call.id()),
                java.util.Optional.empty(),
                0,
                RuntimePhase.BEFORE_DECISION_EXECUTION,
                "tool.execute",
                java.util.Map.of("toolName", definition.name(), "toolVersion", definition.version()),
                time.now()));
        try (var permit = environment.acquire(run, definition)) {
            ToolResult result = retries.execute(
                    () -> {
                        if (run.usage().toolCalls() >= run.budget().maxToolCalls()) {
                            throw new RuntimeLimitExceededException("tool call budget exhausted");
                        }
                        transitions.usage(run, new AgentRunUsageDelta(0, 0, 0, 0, 1, 0, 0, 0));
                        return resultNormalizer.normalize(definition, executor.execute(run, definition, request));
                    },
                    retryPolicy.forTool(definition));
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
                                java.util.Map.of("tool", definition.name()),
                                time.now())),
                        time.now());
            }
            journal.recordCompleted(run.id(), request.idempotencyKey(), result);
            events.append(
                    run.id(),
                    result.successful() ? "tool.completed" : "tool.business-failed",
                    java.util.Map.of("toolCallId", call.id().value(), "toolName", definition.name()),
                    time.now());
            trace.record(new RuntimeTraceEvent(
                    ids.nextValue(),
                    run.id(),
                    java.util.Optional.empty(),
                    run.sessionId(),
                    java.util.Optional.of(stepId),
                    java.util.Optional.of(call.id()),
                    java.util.Optional.empty(),
                    0,
                    RuntimePhase.AFTER_DECISION_EXECUTION,
                    "tool.persisted",
                    java.util.Map.of("successful", result.successful(), "truncated", result.truncated()),
                    time.now()));
            return result;
        } catch (CancellationObservedException cancelled) {
            throw cancelled;
        } catch (RuntimeException exception) {
            journal.recordUncertain(run.id(), request.idempotencyKey());
            throw exception;
        }
    }

    public boolean hasUncertainExecution(AgentRun run) {
        return journal.hasUncertain(run.id());
    }

    private void checkCancellation(AgentRun run) {
        if (controls.signal(run.id()) == RunControlSignal.CANCEL) throw new CancellationObservedException();
    }
}
