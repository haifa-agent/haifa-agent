package io.haifa.agent.runtime.core.decision;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.checkpoint.CheckpointType;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCategory;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.error.AgentErrorSeverity;
import io.haifa.agent.core.error.Retryability;
import io.haifa.agent.core.message.AgentMessage;
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
import io.haifa.agent.core.step.AgentStepType;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.core.checkpoint.CheckpointManager;
import io.haifa.agent.runtime.core.completion.CompletionGuard;
import io.haifa.agent.runtime.core.completion.RunFinalizer;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.control.RunControlSignal;
import io.haifa.agent.runtime.core.delegation.DelegationPort;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.interaction.InteractionRequest;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.loop.AgentLoopContext;
import io.haifa.agent.runtime.core.retry.RepairRetryPolicy;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.tool.ToolPipeline;
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
        transitions.beginCompleting(run);
        state.saveOutput(run.id(), decision.summary());
        transitions.completed(run, finalizer.finalizeResult(run, decision));
        return AgentLoopDirective.STOP;
    }

    private AgentLoopDirective executeTools(AgentRun run, ToolCallDecision decision, AgentLoopContext loopContext) {
        for (ToolRequest request : decision.requests()) {
            AgentStep step = new AgentStep(
                    new AgentStepId(ids.nextValue()),
                    run.id(),
                    null,
                    null,
                    AgentStepType.TOOL_EXECUTION,
                    state.steps(run.id()).size() + 1,
                    time.now());
            state.appendStep(step);
            step.start(time.now());
            try {
                var result = tools.execute(run, step.id(), request);
                step.complete(
                        new AgentStepResult(result.summary(), result.structuredData(), result.artifacts()), time.now());
                appendMessage(run, MessageRole.TOOL, result.summary(), MessageVisibility.AGENT_VISIBLE);
                checkpoints.capture(run, loopContext.iteration(), loopContext.fingerprints(), CheckpointType.AUTOMATIC);
                if (controls.signal(run.id()) == RunControlSignal.CANCEL) {
                    throw new CancellationObservedException();
                }
                if (controls.signal(run.id()) == RunControlSignal.PAUSE) break;
            } catch (IllegalArgumentException | SecurityException repairable) {
                repairRetry.check(loopContext.recordRepairAttempt());
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
                appendMessage(
                        run,
                        MessageRole.TOOL,
                        "Tool request rejected; repair the arguments or choose another capability.",
                        MessageVisibility.AGENT_VISIBLE);
            }
        }
        return AgentLoopDirective.CONTINUE;
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
        checkpoints.capture(run, loopContext.iteration(), loopContext.fingerprints(), CheckpointType.AUTOMATIC);
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
        checkpoints.capture(run, loopContext.iteration(), loopContext.fingerprints(), CheckpointType.INTERACTION);
        transitions.waiting(run, new InteractionRequestRef(requestId, decision.interactionType()), decision.approval());
        return AgentLoopDirective.WAIT;
    }

    private void appendMessage(AgentRun run, MessageRole role, String text, MessageVisibility visibility) {
        appendMessage(run, role, text, visibility, Map.of());
    }

    private void appendMessage(
            AgentRun run, MessageRole role, String text, MessageVisibility visibility, Map<String, Object> metadata) {
        state.appendMessage(new AgentMessage(
                new AgentMessageId(ids.nextValue()),
                run.sessionId(),
                Optional.of(run.id()),
                Optional.empty(),
                role,
                MessageStatus.COMPLETED,
                visibility,
                state.messages(run.id()).size() + 1L,
                List.of(new TextPart(text, "plain")),
                metadata,
                time.now()));
    }
}
