package io.haifa.agent.runtime.core.decision;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.checkpoint.CheckpointType;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCategory;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.error.AgentErrorSeverity;
import io.haifa.agent.core.error.Retryability;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.reference.InteractionRequestRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunUsageDelta;
import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.step.AgentStepError;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.step.AgentStepResult;
import io.haifa.agent.core.step.AgentStepStatus;
import io.haifa.agent.core.step.AgentStepType;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.core.checkpoint.CheckpointManager;
import io.haifa.agent.runtime.core.completion.CompletionGuard;
import io.haifa.agent.runtime.core.completion.RunFinalizer;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.control.RunControlSignal;
import io.haifa.agent.runtime.core.delegation.DelegationPort;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.interaction.InteractionRequest;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.loop.AgentLoopContext;
import io.haifa.agent.runtime.core.model.ModelInvocationResult;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationDraft;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationRef;
import io.haifa.agent.runtime.core.retry.RepairRetryPolicy;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import io.haifa.agent.runtime.core.tool.ToolPipeline;
import io.haifa.agent.runtime.core.tool.ToolPipelineOutcome;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Executes validated decisions without deciding Core lifecycle legality. */
public final class DecisionExecutor {
    private final ToolPipeline tools;
    private final CompletionGuard completionGuard;
    private final RunFinalizer finalizer;
    private final InteractionPort interactions;
    private final DelegationPort delegations;
    private final RuntimeStateRepository state;
    private final RunTransitionCoordinator transitions;
    private final IdentifierGenerator ids;
    private final TimeProvider time;
    private final CheckpointManager checkpoints;
    private final RunControlRegistry controls;
    private final RepairRetryPolicy repairRetry;

    public DecisionExecutor(
            ToolPipeline tools,
            CompletionGuard completionGuard,
            RunFinalizer finalizer,
            InteractionPort interactions,
            DelegationPort delegations,
            RuntimeStateRepository state,
            RunTransitionCoordinator transitions,
            IdentifierGenerator ids,
            TimeProvider time,
            CheckpointManager checkpoints,
            RunControlRegistry controls,
            RepairRetryPolicy repairRetry) {
        this.tools = Objects.requireNonNull(tools);
        this.completionGuard = Objects.requireNonNull(completionGuard);
        this.finalizer = Objects.requireNonNull(finalizer);
        this.interactions = Objects.requireNonNull(interactions);
        this.delegations = Objects.requireNonNull(delegations);
        this.state = Objects.requireNonNull(state);
        this.transitions = Objects.requireNonNull(transitions);
        this.ids = Objects.requireNonNull(ids);
        this.time = Objects.requireNonNull(time);
        this.checkpoints = Objects.requireNonNull(checkpoints);
        this.controls = Objects.requireNonNull(controls);
        this.repairRetry = Objects.requireNonNull(repairRetry);
    }

    public AgentLoopDirective execute(AgentRun run, AgentDecision decision, AgentLoopContext loopContext) {
        if (decision instanceof FinalAnswerDecision finalDecision) return executeFinal(run, finalDecision, loopContext);
        if (decision instanceof ToolCallDecision toolDecision) return executeTools(run, toolDecision, loopContext);
        if (decision instanceof DelegationDecision delegation) return executeDelegation(run, delegation, loopContext);
        if (decision instanceof InteractionDecision interaction)
            return executeInteraction(run, interaction, loopContext);
        ContinueDecision continuation = (ContinueDecision) decision;
        appendMessage(run, MessageRole.ASSISTANT, continuation.message(), MessageVisibility.USER_VISIBLE);
        return AgentLoopDirective.CONTINUE;
    }

    public AgentLoopDirective executeModel(
            AgentRun run, ModelInvocationResult invocation, AgentLoopContext loopContext) {
        AgentDecision decision = invocation.decision();
        if (decision instanceof ToolCallDecision toolDecision) {
            return executeTools(run, toolDecision, loopContext, java.util.Optional.of(invocation));
        }
        return execute(run, decision, loopContext);
    }

    private AgentLoopDirective executeFinal(AgentRun run, FinalAnswerDecision decision, AgentLoopContext loopContext) {
        var readiness = completionGuard.evaluate(run, decision);
        if (!readiness.ready()) {
            repairRetry.check(loopContext.recordRepairAttempt());
            appendMessage(
                    run,
                    MessageRole.RUNTIME,
                    "Completion deferred: " + String.join(", ", readiness.blockers()),
                    MessageVisibility.INTERNAL);
            return AgentLoopDirective.CONTINUE;
        }
        transitions.completedWithOutput(
                run,
                finalizer.finalizeResult(run, decision),
                decision.summary(),
                messageDraft(
                        run,
                        MessageRole.ASSISTANT,
                        List.of(new TextPart(decision.summary(), "plain")),
                        MessageVisibility.USER_VISIBLE,
                        Map.of("final", true)));
        return AgentLoopDirective.STOP;
    }

    private AgentLoopDirective executeTools(AgentRun run, ToolCallDecision decision, AgentLoopContext loopContext) {
        return executeTools(run, decision, loopContext, java.util.Optional.empty());
    }

    private AgentLoopDirective executeTools(
            AgentRun run,
            ToolCallDecision decision,
            AgentLoopContext loopContext,
            java.util.Optional<ModelInvocationResult> invocation) {
        List<PreparedTool> prepared = decision.requests().stream()
                .map(request -> prepareTool(run, request))
                .toList();
        appendToolCalls(run, prepared.stream().map(PreparedTool::call).toList(), invocation);
        for (PreparedTool preparedTool : prepared) {
            ToolRequest request = preparedTool.request();
            ToolCall call = preparedTool.call();
            AgentStep step = preparedTool.step();
            step.start(time.now());
            try {
                ToolPipelineOutcome outcome = tools.execute(run, call, request);
                if (outcome instanceof ToolPipelineOutcome.ApprovalRequired approval) {
                    step.waitForExternalInput();
                    createToolApproval(run, call, approval, loopContext);
                    return AgentLoopDirective.WAIT;
                }
                var result = ((ToolPipelineOutcome.Completed) outcome).result();
                step.complete(
                        new AgentStepResult(result.summary(), result.structuredData(), result.artifacts()), time.now());
                appendToolResult(run, call, result.summary());
                checkpoints.capture(
                        run,
                        loopContext.iteration(),
                        loopContext.fingerprints(),
                        loopContext.forcedContextRebuildAttempts(),
                        CheckpointType.AUTOMATIC);
                if (controls.signal(run.id()) == RunControlSignal.CANCEL) {
                    throw new CancellationObservedException();
                }
                if (controls.signal(run.id()) == RunControlSignal.PAUSE) break;
            } catch (IllegalArgumentException | SecurityException repairable) {
                repairRetry.check(loopContext.recordRepairAttempt());
                cancelRejectedCall(call);
                step.fail(
                        new AgentStepError(new AgentError(
                                new AgentErrorCode("TOOL_REQUEST_REJECTED"),
                                AgentErrorCategory.VALIDATION,
                                AgentErrorSeverity.WARNING,
                                Retryability.NOT_RETRYABLE,
                                "Tool request validation failed",
                                null,
                                Map.of("reason", repairable.getClass().getSimpleName()),
                                time.now())),
                        time.now());
                appendToolResult(
                        run, call, "Tool request rejected; repair the arguments or choose another capability.");
            }
        }
        return AgentLoopDirective.CONTINUE;
    }

    private void cancelRejectedCall(ToolCall call) {
        switch (call.status()) {
            case COMPLETED, FAILED, DENIED, CANCELLED, TIMEOUT -> {
                // Validation or policy may already have closed the call.
            }
            default -> call.cancel(time.now());
        }
    }

    private void createToolApproval(
            AgentRun run, ToolCall call, ToolPipelineOutcome.ApprovalRequired approval, AgentLoopContext loopContext) {
        String requestId = ids.nextValue();
        var binding = approval.binding();
        String interactionType = approval.reauthentication() ? "tool-reauthentication" : "tool-approval";
        interactions.create(new InteractionRequest(
                new InteractionRequestId(requestId),
                run.id(),
                run.tenant(),
                run.principal(),
                interactionType,
                (approval.reauthentication() ? "Reauthenticate and approve tool " : "Approve tool ")
                        + binding.alias().value() + " ("
                        + binding.coordinate().externalForm() + ")",
                true,
                new ToolApprovalTarget(
                        call.id(),
                        binding.coordinate().externalForm(),
                        binding.coordinate().definitionHash().value(),
                        approval.argumentsDigest(),
                        run.tenant().tenantId() + ":" + run.principal().principalType() + ":"
                                + run.principal().principalId()),
                time.now(),
                time.now().plus(java.time.Duration.ofHours(1))));
        checkpoints.capture(
                run,
                loopContext.iteration(),
                loopContext.fingerprints(),
                loopContext.forcedContextRebuildAttempts(),
                CheckpointType.INTERACTION);
        transitions.waiting(run, new InteractionRequestRef(requestId, interactionType), true);
    }

    public Optional<AgentLoopDirective> resumePendingTools(AgentRun run, AgentLoopContext loopContext) {
        List<ToolCall> pending = state.toolCalls(run.id()).stream()
                .filter(call -> call.status() == ToolCallStatus.REQUESTED || call.status() == ToolCallStatus.APPROVED)
                .toList();
        if (pending.isEmpty()) return Optional.empty();
        for (ToolCall call : pending) {
            AgentStep step = state.steps(run.id()).stream()
                    .filter(candidate -> candidate.id().equals(call.stepId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("tool step is unavailable"));
            if (call.status() == ToolCallStatus.APPROVED && step.status() == AgentStepStatus.WAITING) step.resume();
            else if (call.status() == ToolCallStatus.REQUESTED) step.start(time.now());
            ToolRequest request = requestFrom(call);
            ToolPipelineOutcome outcome = tools.execute(run, call, request);
            if (outcome instanceof ToolPipelineOutcome.ApprovalRequired approval) {
                step.waitForExternalInput();
                createToolApproval(run, call, approval, loopContext);
                return Optional.of(AgentLoopDirective.WAIT);
            }
            var result = ((ToolPipelineOutcome.Completed) outcome).result();
            step.complete(
                    new AgentStepResult(result.summary(), result.structuredData(), result.artifacts()), time.now());
            appendToolResult(run, call, result.summary());
            checkpoints.capture(
                    run,
                    loopContext.iteration(),
                    loopContext.fingerprints(),
                    loopContext.forcedContextRebuildAttempts(),
                    CheckpointType.AUTOMATIC);
        }
        return Optional.of(AgentLoopDirective.CONTINUE);
    }

    public void resolveToolApproval(AgentRun run, ToolApprovalTarget target, InteractionResponseType responseType) {
        ToolCall call = state.toolCalls(run.id()).stream()
                .filter(candidate -> candidate.id().equals(target.toolCallId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("tool approval target is unavailable"));
        tools.validateApprovalTarget(run, call, requestFrom(call), target);
        if (responseType == InteractionResponseType.APPROVE) {
            call.approve();
            return;
        }
        if (responseType != InteractionResponseType.REJECT) {
            throw new IllegalArgumentException("tool approval requires approve or reject");
        }
        call.deny(time.now());
        AgentStep step = state.steps(run.id()).stream()
                .filter(candidate -> candidate.id().equals(call.stepId()))
                .findFirst()
                .orElseThrow();
        step.resume();
        step.fail(
                new AgentStepError(new AgentError(
                        new AgentErrorCode("TOOL_APPROVAL_REJECTED"),
                        AgentErrorCategory.TOOL,
                        AgentErrorSeverity.WARNING,
                        Retryability.NOT_RETRYABLE,
                        "Tool execution was rejected by the operator",
                        null,
                        Map.of("toolCallId", call.id().value()),
                        time.now())),
                time.now());
        appendToolResult(run, call, "Tool execution was rejected by the operator.");
    }

    public void applyPendingToolApproval(AgentRun run) {
        interactions.unappliedToolResolution(run.id()).ifPresent(resolution -> {
            ToolApprovalTarget target =
                    (ToolApprovalTarget) resolution.request().target();
            resolveToolApproval(run, target, resolution.response().type());
            interactions.markResolutionApplied(resolution.request().id());
        });
    }

    private static ToolRequest requestFrom(ToolCall call) {
        return new ToolRequest(
                call.id(),
                call.providerCorrelationId(),
                call.idempotencyKey(),
                call.toolName(),
                call.toolVersion(),
                call.arguments());
    }

    private AgentLoopDirective executeDelegation(
            AgentRun run, DelegationDecision decision, AgentLoopContext loopContext) {
        var result = delegations.executeChild(run, decision);
        appendMessage(
                run,
                MessageRole.AGENT,
                result.summary(),
                MessageVisibility.AGENT_VISIBLE,
                Map.of(
                        "outcome", result.outcome().name(),
                        "structuredOutput", result.structuredOutput(),
                        "artifacts", result.artifacts(),
                        "warnings", result.warnings()));
        transitions.usage(run, new AgentRunUsageDelta(0, 0, 0, 0, 0, 1, 0, 0));
        checkpoints.capture(
                run,
                loopContext.iteration(),
                loopContext.fingerprints(),
                loopContext.forcedContextRebuildAttempts(),
                CheckpointType.AUTOMATIC);
        if (controls.signal(run.id()) == RunControlSignal.CANCEL) throw new CancellationObservedException();
        return AgentLoopDirective.CONTINUE;
    }

    private AgentLoopDirective executeInteraction(
            AgentRun run, InteractionDecision decision, AgentLoopContext loopContext) {
        String requestId = ids.nextValue();
        interactions.create(new InteractionRequest(
                new InteractionRequestId(requestId),
                run.id(),
                run.tenant(),
                run.principal(),
                decision.interactionType(),
                decision.prompt(),
                decision.approval(),
                time.now(),
                time.now().plus(java.time.Duration.ofHours(1))));
        checkpoints.capture(
                run,
                loopContext.iteration(),
                loopContext.fingerprints(),
                loopContext.forcedContextRebuildAttempts(),
                CheckpointType.INTERACTION);
        transitions.waiting(run, new InteractionRequestRef(requestId, decision.interactionType()), decision.approval());
        return AgentLoopDirective.WAIT;
    }

    private void appendMessage(AgentRun run, MessageRole role, String text, MessageVisibility visibility) {
        appendMessage(run, role, text, visibility, Map.of());
    }

    private void appendMessage(
            AgentRun run, MessageRole role, String text, MessageVisibility visibility, Map<String, Object> metadata) {
        appendMessage(run, role, List.of(new TextPart(text, "plain")), visibility, metadata);
    }

    private PreparedTool prepareTool(AgentRun run, ToolRequest request) {
        AgentStep step = new AgentStep(
                new AgentStepId(ids.nextValue()),
                run.id(),
                null,
                null,
                AgentStepType.TOOL_EXECUTION,
                state.steps(run.id()).size() + 1,
                time.now());
        state.appendStep(step);
        return new PreparedTool(request, tools.prepare(run, step.id(), request), step);
    }

    private void appendToolCalls(
            AgentRun run, List<ToolCall> calls, java.util.Optional<ModelInvocationResult> invocation) {
        List<ContentPart> parts = calls.stream()
                .map(call -> (ContentPart)
                        new ToolCallPart(call.id(), call.providerCorrelationId(), call.toolName(), call.toolVersion()))
                .toList();
        var continuationInvocation =
                invocation.filter(value -> value.reasoning().isPresent());
        if (continuationInvocation.isEmpty()) {
            appendMessage(run, MessageRole.ASSISTANT, parts, MessageVisibility.AGENT_VISIBLE, Map.of());
            return;
        }
        ModelInvocationResult value = continuationInvocation.orElseThrow();
        var reasoning = value.reasoning().orElseThrow();
        ModelContinuationRef reference =
                new ModelContinuationRef(ids.nextValue(), "1.0", reasoning.digest(), reasoning.byteLength());
        Map<String, Object> metadata = Map.of(
                "modelContinuationId",
                reference.id(),
                "modelContinuationVersion",
                reference.version(),
                "modelContinuationDigest",
                reference.digest(),
                "modelContinuationBytes",
                reference.byteLength());
        SessionMessageDraft message =
                messageDraft(run, MessageRole.ASSISTANT, parts, MessageVisibility.AGENT_VISIBLE, metadata);
        var correlations = calls.stream()
                .map(call -> call.providerCorrelationId().value())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        state.appendSessionMessageWithContinuation(
                message,
                new ModelContinuationDraft(
                        reference,
                        run.id(),
                        run.sessionId(),
                        value.modelCallId(),
                        value.model().providerId().value(),
                        value.model().providerModelId(),
                        value.model().configurationDigest(),
                        correlations,
                        reasoning,
                        time.now()));
    }

    private void appendToolResult(AgentRun run, ToolCall call, String text) {
        appendMessage(
                run,
                MessageRole.TOOL,
                List.of(new ToolResultPart(call.id(), call.providerCorrelationId(), text)),
                MessageVisibility.AGENT_VISIBLE,
                Map.of());
    }

    private void appendMessage(
            AgentRun run,
            MessageRole role,
            List<ContentPart> contents,
            MessageVisibility visibility,
            Map<String, Object> metadata) {
        state.appendSessionMessage(messageDraft(run, role, contents, visibility, metadata));
    }

    private SessionMessageDraft messageDraft(
            AgentRun run,
            MessageRole role,
            List<ContentPart> contents,
            MessageVisibility visibility,
            Map<String, Object> metadata) {
        return new SessionMessageDraft(
                new AgentMessageId(ids.nextValue()),
                run.sessionId(),
                Optional.of(run.id()),
                Optional.empty(),
                role,
                MessageStatus.COMPLETED,
                visibility,
                contents,
                metadata,
                time.now());
    }

    private record PreparedTool(ToolRequest request, ToolCall call, AgentStep step) {}
}
