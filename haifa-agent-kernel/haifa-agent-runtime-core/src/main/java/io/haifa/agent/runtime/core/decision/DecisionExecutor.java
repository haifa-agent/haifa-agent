package io.haifa.agent.runtime.core.decision;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.checkpoint.CheckpointType;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.reference.InteractionRequestRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.AgentRunResult;
import io.haifa.agent.core.run.AgentRunUsageDelta;
import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.step.AgentStepError;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.step.AgentStepResult;
import io.haifa.agent.core.step.AgentStepStatus;
import io.haifa.agent.core.step.AgentStepType;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.policy.api.ApprovalRequestContext;
import io.haifa.agent.policy.api.ApprovalRequester;
import io.haifa.agent.policy.api.ApprovalReuseScope;
import io.haifa.agent.policy.api.ApprovalSemantics;
import io.haifa.agent.policy.api.ApprovalTargetRef;
import io.haifa.agent.policy.api.PolicyDecisionStore;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.core.checkpoint.CheckpointManager;
import io.haifa.agent.runtime.core.completion.CompletionBlocker;
import io.haifa.agent.runtime.core.completion.CompletionGuard;
import io.haifa.agent.runtime.core.completion.RunFinalizer;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.control.RunControlSignal;
import io.haifa.agent.runtime.core.delegation.DelegationPort;
import io.haifa.agent.runtime.core.execution.AgentExecutionFailureException;
import io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.interaction.InteractionRequest;
import io.haifa.agent.runtime.core.interaction.ToolApprovalPromptFormatter;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.loop.AgentLoopContext;
import io.haifa.agent.runtime.core.model.ModelInvocationResult;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationDraft;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationRef;
import io.haifa.agent.runtime.core.recovery.BudgetLimitedSummary;
import io.haifa.agent.runtime.core.retry.RepairRetryPolicy;
import io.haifa.agent.runtime.core.storage.OutboxMessage;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimeOutboxPublisher;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeUnitOfWork;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import io.haifa.agent.runtime.core.tool.ToolInputValidationException;
import io.haifa.agent.runtime.core.tool.ToolPipeline;
import io.haifa.agent.runtime.core.tool.ToolPipelineOutcome;
import java.util.ArrayList;
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
    private final ToolApprovalPromptFormatter approvalPrompts;
    private final PolicyDecisionStore policyDecisions;
    private final RuntimeUnitOfWork unitOfWork;
    private final RuntimeEventAppender events;
    private final RuntimeOutboxPublisher outbox;

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
            RepairRetryPolicy repairRetry,
            ToolApprovalPromptFormatter approvalPrompts,
            PolicyDecisionStore policyDecisions,
            RuntimeUnitOfWork unitOfWork,
            RuntimeEventAppender events,
            RuntimeOutboxPublisher outbox) {
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
        this.approvalPrompts = Objects.requireNonNull(approvalPrompts);
        this.policyDecisions = Objects.requireNonNull(policyDecisions);
        this.unitOfWork = Objects.requireNonNull(unitOfWork);
        this.events = Objects.requireNonNull(events);
        this.outbox = Objects.requireNonNull(outbox);
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

    public boolean mayModifyWorkspace(AgentRun run, AgentDecision decision) {
        return decision instanceof ToolCallDecision toolsDecision
                && toolsDecision.requests().stream().anyMatch(request -> tools.mayModifyWorkspace(run, request));
    }

    public void failWithSummary(AgentRun run, AgentError error, String summary) {
        transitions.failedWithOutput(
                run,
                error,
                summary,
                messageDraft(
                        run,
                        MessageRole.ASSISTANT,
                        List.of(new TextPart(summary, "plain")),
                        MessageVisibility.USER_VISIBLE,
                        Map.of(
                                "final",
                                true,
                                "partial",
                                true,
                                "terminalErrorCode",
                                error.code().wireCode(),
                                "terminalSummaryVersion",
                                "1")));
    }

    public boolean supportsBudgetLimitedCompletion(AgentRun run) {
        return state.configuration(run.configurationSnapshot())
                .flatMap(configuration -> configuration.structuredOutput())
                .isEmpty();
    }

    public boolean completeBudgetLimited(
            AgentRun run, RuntimeLimitExceededException limit, Optional<FinalAnswerDecision> finalDecision) {
        if (!supportsBudgetLimitedCompletion(run)) return false;
        String resource = upperSnake(limit.resource());
        FinalAnswerDecision candidate = finalDecision.orElse(null);
        String summary = candidate == null
                ? BudgetLimitedSummary.create(resource, limit.used(), limit.limit(), state.toolCalls(run.id()))
                : candidate.summary();
        List<String> warnings = new ArrayList<>(candidate == null ? List.of() : candidate.warnings());
        warnings.add("BUDGET_LIMITED:" + resource);
        AgentRunResult result = new AgentRunResult(
                AgentRunOutcome.PARTIAL_SUCCESS,
                summary,
                candidate == null ? "haifa.agent.partial-result" : candidate.outputSchemaId(),
                candidate == null ? "1" : candidate.outputSchemaVersion(),
                candidate == null
                        ? Map.of(
                                "completionReason",
                                "BUDGET_LIMITED",
                                "limitingResource",
                                resource,
                                "used",
                                limit.used(),
                                "limit",
                                limit.limit())
                        : candidate.structuredOutput(),
                candidate == null ? List.of() : candidate.artifacts(),
                warnings.stream().distinct().toList());
        transitions.completedWithOutput(
                run,
                result,
                summary,
                messageDraft(
                        run,
                        MessageRole.ASSISTANT,
                        List.of(new TextPart(summary, "plain")),
                        MessageVisibility.USER_VISIBLE,
                        Map.of(
                                "final",
                                true,
                                "partial",
                                true,
                                "completionReason",
                                "BUDGET_LIMITED",
                                "limitingResource",
                                resource,
                                "limitingUsed",
                                limit.used(),
                                "limitingLimit",
                                limit.limit())));
        return true;
    }

    private AgentLoopDirective executeFinal(AgentRun run, FinalAnswerDecision decision, AgentLoopContext loopContext) {
        var readiness = completionGuard.evaluate(run, decision);
        if (!readiness.ready()) {
            List<String> missingEvidence = readiness.blockers().stream()
                    .map(CompletionBlocker::evidenceRequirement)
                    .distinct()
                    .sorted()
                    .toList();
            List<String> blockerCodes = readiness.blockers().stream()
                    .map(CompletionBlocker::code)
                    .distinct()
                    .sorted()
                    .toList();
            List<String> repairGuidance = readiness.blockers().stream()
                    .sorted(java.util.Comparator.comparing(CompletionBlocker::code))
                    .map(blocker -> blocker.code() + ": " + blocker.safeMessage())
                    .distinct()
                    .toList();
            if (blockerCodes.contains("STRUCTURED_OUTPUT_INVALID")) {
                events.append(
                        run.id(),
                        "run.structured-termination",
                        Map.of(
                                "reason",
                                "STRUCTURED_OUTPUT_INVALID",
                                "attempts",
                                0,
                                "blockerCodes",
                                blockerCodes,
                                "missingEvidence",
                                missingEvidence),
                        time.now());
                transitions.failed(
                        run,
                        new AgentError(
                                AgentErrorCode.MODEL_STRUCTURED_OUTPUT_INVALID,
                                Map.of("blockerCodes", blockerCodes, "missingEvidence", missingEvidence),
                                ids.nextValue(),
                                time.now()));
                return AgentLoopDirective.STOP;
            }
            int attempt = loopContext.recordRepairAttempt();
            int remainingPercent = loopContext
                    .budgetSnapshot()
                    .map(value -> value.remainingPercent())
                    .orElse(0);
            if (attempt > repairRetry.maxAttempts()) {
                events.append(
                        run.id(),
                        "run.structured-termination",
                        Map.of(
                                "reason",
                                "COMPLETION_REPAIR_EXHAUSTED",
                                "attempts",
                                attempt - 1,
                                "blockerCodes",
                                blockerCodes,
                                "missingEvidence",
                                missingEvidence),
                        time.now());
                transitions.failed(
                        run,
                        new AgentError(
                                blockerCodes.contains("STRUCTURED_OUTPUT_INVALID")
                                        ? AgentErrorCode.MODEL_STRUCTURED_OUTPUT_INVALID
                                        : AgentErrorCode.COMPLETION_REPAIR_EXHAUSTED,
                                Map.of(
                                        "blockerCodes", blockerCodes,
                                        "missingEvidence", missingEvidence,
                                        "attempts", attempt - 1),
                                ids.nextValue(),
                                time.now()));
                return AgentLoopDirective.STOP;
            }
            String phase = blockerCodes.stream().anyMatch(code -> code.contains("VALIDATION") || code.contains("DIFF"))
                    ? "VERIFYING"
                    : "RECOVERING";
            events.append(
                    run.id(),
                    "completion.deferred",
                    Map.of(
                            "phase",
                            phase,
                            "status",
                            "COMPLETION_DEFERRED",
                            "reasonCode",
                            blockerCodes.getFirst(),
                            "blockerCodes",
                            blockerCodes,
                            "missingEvidence",
                            missingEvidence,
                            "evidenceCodes",
                            readiness.evidenceCodes(),
                            "attempt",
                            attempt,
                            "maximumAttempts",
                            repairRetry.maxAttempts(),
                            "remainingPercent",
                            remainingPercent),
                    time.now());
            appendMessage(
                    run,
                    MessageRole.RUNTIME,
                    structuredCorrection(
                            phase,
                            attempt,
                            repairRetry.maxAttempts(),
                            blockerCodes,
                            readiness.evidenceCodes(),
                            missingEvidence,
                            repairGuidance,
                            remainingPercent),
                    MessageVisibility.AGENT_VISIBLE,
                    Map.of(
                            "completionRepair",
                            true,
                            "completionRepairAttempt",
                            attempt,
                            "completionBlockerCodes",
                            blockerCodes,
                            "completionEvidenceCodes",
                            readiness.evidenceCodes()));
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

    private static String structuredCorrection(
            String phase,
            int attempt,
            int maximumAttempts,
            List<String> blockerCodes,
            List<String> evidenceCodes,
            List<String> missingEvidence,
            List<String> repairGuidance,
            int remainingPercent) {
        return String.join(
                "\n",
                "[DELIVERY_COMPLETION_REPAIR]",
                "phase=" + phase,
                "attempt=" + attempt + "/" + maximumAttempts,
                "blockers=" + String.join("|", blockerCodes),
                "evidence=" + (evidenceCodes.isEmpty() ? "NONE" : String.join("|", evidenceCodes)),
                "missing=" + String.join("|", missingEvidence),
                "guidance=" + String.join(" || ", repairGuidance),
                "remainingPercent=" + remainingPercent,
                "nextAction=collect the smallest authoritative missing evidence, then submit final output");
    }

    private AgentLoopDirective executeTools(AgentRun run, ToolCallDecision decision, AgentLoopContext loopContext) {
        return executeTools(run, decision, loopContext, java.util.Optional.empty());
    }

    private AgentLoopDirective executeTools(
            AgentRun run,
            ToolCallDecision decision,
            AgentLoopContext loopContext,
            java.util.Optional<ModelInvocationResult> invocation) {
        long projectedToolCalls = run.usage().toolCalls() + decision.requests().size();
        if (projectedToolCalls > run.budget().maxToolCalls()) {
            RuntimeLimitExceededException limit =
                    new RuntimeLimitExceededException("toolCalls", run.budget().maxToolCalls(), projectedToolCalls);
            if (completeBudgetLimited(run, limit, Optional.empty())) return AgentLoopDirective.STOP;
            throw limit;
        }
        List<PreparedTool> prepared = decision.requests().stream()
                .map(request -> prepareTool(run, request))
                .toList();
        appendToolCalls(run, prepared.stream().map(PreparedTool::call).toList(), invocation);
        for (PreparedTool preparedTool : prepared) {
            ToolRequest request = preparedTool.request();
            ToolCall call = preparedTool.call();
            AgentStep step = preparedTool.step();
            step.start(time.now());
            state.appendStep(step);
            ToolPipelineOutcome outcome;
            try {
                outcome = tools.execute(run, call, request, loopContext.iteration());
            } catch (ToolInputValidationException validation) {
                rejectToolRequest(
                        run, call, step, loopContext, validation, "Tool request rejected. " + validation.repairHint());
                continue;
            } catch (IllegalArgumentException | SecurityException repairable) {
                rejectToolRequest(
                        run,
                        call,
                        step,
                        loopContext,
                        repairable,
                        "Tool request rejected; repair the arguments or choose another capability.");
                continue;
            } catch (RuntimeException failure) {
                throw failToolAndCancelPendingSiblings(run, call, step, failure);
            }
            if (outcome instanceof ToolPipelineOutcome.ApprovalRequired approval) {
                step.waitForExternalInput();
                state.appendStep(step);
                state.appendToolCall(call);
                createToolApproval(run, call, approval, loopContext);
                return AgentLoopDirective.WAIT;
            }
            var result = ((ToolPipelineOutcome.Completed) outcome).result();
            step.complete(
                    new AgentStepResult(result.summary(), result.structuredData(), result.artifacts()), time.now());
            state.appendStep(step);
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
        }
        return AgentLoopDirective.CONTINUE;
    }

    private void rejectToolRequest(
            AgentRun run,
            ToolCall call,
            AgentStep step,
            AgentLoopContext loopContext,
            RuntimeException failure,
            String modelSummary) {
        repairRetry.check(loopContext.recordRepairAttempt());
        cancelRejectedCall(call);
        state.appendToolCall(call);
        Map<String, Object> attributes = failure instanceof ToolInputValidationException validation
                ? Map.of("reason", "ARGUMENTS_INVALID", "repairHint", validation.repairHint())
                : Map.of("reason", "TOOL_REQUEST_REJECTED");
        step.fail(
                new AgentStepError(
                        new AgentError(AgentErrorCode.TOOL_REQUEST_REJECTED, attributes, ids.nextValue(), time.now())),
                time.now());
        state.appendStep(step);
        appendToolResult(run, call, modelSummary);
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
        var createdAt = time.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var approvalContext = new ApprovalRequestContext(
                approval.decision().id(),
                ApprovalSemantics.CAPABILITY_CONFIRMATION,
                java.util.Set.of(ApprovalReuseScope.ONCE),
                new ApprovalRequester(run.tenant(), run.principal()),
                new ApprovalTargetRef(
                        "tool",
                        call.id().value(),
                        binding.coordinate().definitionHash().value(),
                        "invoke",
                        approval.argumentsDigest(),
                        binding.definition().title()),
                Optional.empty(),
                createdAt,
                Optional.empty(),
                Optional.empty());
        unitOfWork.execute(() -> {
            interactions.create(new InteractionRequest(
                    new InteractionRequestId(requestId),
                    run.id(),
                    run.tenant(),
                    run.principal(),
                    interactionType,
                    approvalPrompts.format(binding, call, approval.reauthentication()),
                    true,
                    new ToolApprovalTarget(
                            call.id(),
                            binding.coordinate().externalForm(),
                            binding.coordinate().definitionHash().value(),
                            approval.argumentsDigest(),
                            run.tenant().tenantId() + ":" + run.principal().principalType() + ":"
                                    + run.principal().principalId()),
                    createdAt,
                    Optional.empty(),
                    Optional.of(approvalContext)));
            checkpoints.capture(
                    run,
                    loopContext.iteration(),
                    loopContext.fingerprints(),
                    loopContext.forcedContextRebuildAttempts(),
                    CheckpointType.INTERACTION);
            transitions.waiting(run, new InteractionRequestRef(requestId, interactionType), true);
            appendSecurityEvent(
                    run,
                    "policy.decision.made",
                    Map.of(
                            "decisionId",
                            approval.decision().id().value(),
                            "snapshotId",
                            approval.decision().snapshot().value(),
                            "effect",
                            approval.decision().effect().name(),
                            "challenge",
                            approval.decision().challenge().orElseThrow().name(),
                            "reasonCode",
                            approval.decision().reasonCode()),
                    createdAt);
            appendSecurityEvent(
                    run,
                    "approval.requested",
                    Map.of(
                            "requestId",
                            requestId,
                            "decisionId",
                            approval.decision().id().value(),
                            "challenge",
                            approval.reauthentication() ? "REAUTHENTICATE" : "APPROVAL",
                            "semantics",
                            ApprovalSemantics.CAPABILITY_CONFIRMATION.name()),
                    createdAt);
            return null;
        });
    }

    private void appendSecurityEvent(AgentRun run, String type, Map<String, Object> data, java.time.Instant at) {
        var event = events.append(run.id(), type, data, at);
        outbox.append(new OutboxMessage(
                event.eventId(),
                event.runId(),
                event.sequence(),
                event.type(),
                OutboxMessage.CURRENT_SCHEMA_VERSION,
                event.data(),
                event.occurredAt()));
    }

    public Optional<AgentLoopDirective> resumePendingTools(AgentRun run, AgentLoopContext loopContext) {
        List<PendingTool> pending = state.toolCalls(run.id()).stream()
                .filter(call -> call.status() == ToolCallStatus.REQUESTED || call.status() == ToolCallStatus.APPROVED)
                .map(call -> new PendingTool(
                        call,
                        state.steps(run.id()).stream()
                                .filter(candidate -> candidate.id().equals(call.stepId()))
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException("tool step is unavailable"))))
                .sorted(java.util.Comparator.comparingInt(value -> value.step().sequence()))
                .toList();
        if (pending.isEmpty()) return Optional.empty();
        for (PendingTool pendingTool : pending) {
            ToolCall call = pendingTool.call();
            AgentStep step = pendingTool.step();
            if (call.status() == ToolCallStatus.APPROVED && step.status() == AgentStepStatus.WAITING) step.resume();
            else if (call.status() == ToolCallStatus.REQUESTED) step.start(time.now());
            state.appendStep(step);
            ToolRequest request = requestFrom(call);
            ToolPipelineOutcome outcome;
            try {
                outcome = tools.execute(run, call, request, loopContext.iteration());
            } catch (RuntimeException failure) {
                throw failToolAndCancelPendingSiblings(run, call, step, failure);
            }
            if (outcome instanceof ToolPipelineOutcome.ApprovalRequired approval) {
                step.waitForExternalInput();
                state.appendStep(step);
                state.appendToolCall(call);
                createToolApproval(run, call, approval, loopContext);
                return Optional.of(AgentLoopDirective.WAIT);
            }
            var result = ((ToolPipelineOutcome.Completed) outcome).result();
            step.complete(
                    new AgentStepResult(result.summary(), result.structuredData(), result.artifacts()), time.now());
            state.appendStep(step);
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

    private record PendingTool(ToolCall call, AgentStep step) {}

    private AgentExecutionFailureException failToolAndCancelPendingSiblings(
            AgentRun run, ToolCall failedCall, AgentStep failedStep, RuntimeException failure) {
        AgentError toolError = failure instanceof AgentExecutionFailureException classified
                ? classified.error()
                : failedCall
                        .error()
                        .map(io.haifa.agent.core.tool.ToolExecutionError::error)
                        .orElseGet(() -> new AgentError(
                                AgentErrorCode.TOOL_INVOCATION_FAILED,
                                Map.of("tool", failedCall.toolName()),
                                ids.nextValue(),
                                time.now()));
        if (failedStep.status() == AgentStepStatus.RUNNING || failedStep.status() == AgentStepStatus.WAITING) {
            failedStep.fail(new AgentStepError(toolError), time.now());
            state.appendStep(failedStep);
        }
        appendToolResult(run, failedCall, toolError.message());

        for (ToolCall sibling : state.toolCalls(run.id())) {
            if (sibling.id().equals(failedCall.id()) || sibling.startedAt().isPresent() || terminal(sibling.status()))
                continue;
            sibling.cancel(time.now());
            state.appendToolCall(sibling);
            state.steps(run.id()).stream()
                    .filter(step -> step.id().equals(sibling.stepId()))
                    .findFirst()
                    .filter(step -> !terminal(step.status()))
                    .ifPresent(step -> {
                        step.cancel(time.now());
                        state.appendStep(step);
                    });
            appendToolResult(run, sibling, "Tool call was cancelled because another call in the same batch failed.");
            events.append(
                    run.id(),
                    "tool.cancelled",
                    Map.of(
                            "toolCallId",
                            sibling.id().value(),
                            "displayName",
                            sibling.toolName(),
                            "status",
                            "CANCELLED",
                            "reasonCode",
                            "SIBLING_TOOL_FAILED",
                            "targetSummary",
                            sibling.toolName(),
                            "resultRef",
                            ""),
                    time.now());
        }
        return new AgentExecutionFailureException(toolError, failure);
    }

    private static boolean terminal(ToolCallStatus status) {
        return switch (status) {
            case COMPLETED, FAILED, DENIED, CANCELLED, TIMEOUT -> true;
            default -> false;
        };
    }

    private static boolean terminal(AgentStepStatus status) {
        return switch (status) {
            case COMPLETED, FAILED, CANCELLED, SKIPPED -> true;
            default -> false;
        };
    }

    public void resolveToolApproval(
            AgentRun run,
            ToolApprovalTarget target,
            Optional<ApprovalRequestContext> approvalContext,
            InteractionResponseType responseType) {
        ToolCall call = state.toolCalls(run.id()).stream()
                .filter(candidate -> candidate.id().equals(target.toolCallId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("tool approval target is unavailable"));
        tools.validateApprovalTarget(run, call, requestFrom(call), target);
        if (responseType == InteractionResponseType.APPROVE) {
            ApprovalRequestContext context =
                    approvalContext.orElseThrow(() -> new SecurityException("approval context is unavailable"));
            var decision = policyDecisions
                    .find(context.decisionId())
                    .orElseThrow(() -> new SecurityException("policy decision is unavailable"));
            tools.recordApprovedDecision(call, decision);
            call.approve();
            state.appendToolCall(call);
            return;
        }
        if (responseType != InteractionResponseType.REJECT) {
            throw new IllegalArgumentException("tool approval requires approve or reject");
        }
        call.deny(time.now());
        state.appendToolCall(call);
        AgentStep step = state.steps(run.id()).stream()
                .filter(candidate -> candidate.id().equals(call.stepId()))
                .findFirst()
                .orElseThrow();
        step.resume();
        step.fail(
                new AgentStepError(new AgentError(
                        AgentErrorCode.TOOL_APPROVAL_REJECTED,
                        Map.of("toolCallId", call.id().value()),
                        ids.nextValue(),
                        time.now())),
                time.now());
        state.appendStep(step);
        appendToolResult(run, call, "Tool execution was rejected by the operator.");
    }

    public void applyPendingToolApproval(AgentRun run) {
        unitOfWork.execute(() -> {
            interactions.unappliedToolResolution(run.id()).ifPresent(resolution -> {
                ToolApprovalTarget target =
                        (ToolApprovalTarget) resolution.request().target();
                resolveToolApproval(
                        run,
                        target,
                        resolution.request().approvalContext(),
                        resolution.response().type());
                interactions.markResolutionApplied(resolution.request().id());
            });
            return null;
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
        long projectedChildRuns = run.usage().childRuns() + 1;
        if (projectedChildRuns > run.budget().maxChildRuns()) {
            RuntimeLimitExceededException limit =
                    new RuntimeLimitExceededException("childRuns", run.budget().maxChildRuns(), projectedChildRuns);
            if (completeBudgetLimited(run, limit, Optional.empty())) return AgentLoopDirective.STOP;
            throw limit;
        }
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

    private static String upperSnake(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9_]", "_")
                .toUpperCase(java.util.Locale.ROOT);
    }

    private AgentLoopDirective executeInteraction(
            AgentRun run, InteractionDecision decision, AgentLoopContext loopContext) {
        String requestId = ids.nextValue();
        var createdAt = time.now();
        unitOfWork.execute(() -> {
            interactions.create(new InteractionRequest(
                    new InteractionRequestId(requestId),
                    run.id(),
                    run.tenant(),
                    run.principal(),
                    decision.interactionType(),
                    decision.prompt(),
                    decision.approval(),
                    createdAt,
                    Optional.empty()));
            checkpoints.capture(
                    run,
                    loopContext.iteration(),
                    loopContext.fingerprints(),
                    loopContext.forcedContextRebuildAttempts(),
                    CheckpointType.INTERACTION);
            transitions.waiting(
                    run, new InteractionRequestRef(requestId, decision.interactionType()), decision.approval());
            appendSecurityEvent(
                    run,
                    "interaction.requested",
                    Map.of("requestId", requestId, "kind", decision.interactionType()),
                    createdAt);
            return null;
        });
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
        ToolRequest canonicalRequest = tools.canonicalize(run, request);
        AgentStep step = new AgentStep(
                new AgentStepId(ids.nextValue()),
                run.id(),
                null,
                null,
                AgentStepType.TOOL_EXECUTION,
                state.steps(run.id()).size() + 1,
                time.now());
        state.appendStep(step);
        return new PreparedTool(canonicalRequest, tools.prepare(run, step.id(), canonicalRequest), step);
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
