package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.step.AgentStepError;
import io.haifa.agent.core.step.AgentStepResult;
import io.haifa.agent.core.step.AgentStepStatus;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.runtime.core.execution.AgentExecutionFailureException;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import io.haifa.agent.runtime.core.tool.ToolPipeline;
import io.haifa.agent.runtime.core.tool.ToolPipelineOutcome;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Rebuilds durable tool facts before an abandoned execution attempt may continue. */
public final class ToolRecoveryCoordinator {
    private final RuntimeStateRepository state;
    private final ToolPipeline tools;
    private final IdentifierGenerator ids;
    private final TimeProvider time;

    public ToolRecoveryCoordinator(
            RuntimeStateRepository state, ToolPipeline tools, IdentifierGenerator ids, TimeProvider time) {
        this.state = Objects.requireNonNull(state);
        this.tools = Objects.requireNonNull(tools);
        this.ids = Objects.requireNonNull(ids);
        this.time = Objects.requireNonNull(time);
    }

    public void reconcile(AgentRun run) {
        for (ToolCall call : state.toolCalls(run.id())) {
            if (!needsProjectionRecovery(run, call) || !tools.hasRecoveryFacts(run, call)) continue;
            try {
                ToolPipelineOutcome outcome = tools.recover(run, call, 1);
                var result = ((ToolPipelineOutcome.Completed) outcome).result();
                state.steps(run.id()).stream()
                        .filter(step -> step.id().equals(call.stepId()))
                        .filter(step -> !terminal(step.status()))
                        .findFirst()
                        .ifPresent(step -> {
                            if (step.status() == AgentStepStatus.PENDING) step.start(time.now());
                            if (step.status() == AgentStepStatus.WAITING) step.resume();
                            step.complete(
                                    new AgentStepResult(result.summary(), result.structuredData(), result.artifacts()),
                                    time.now());
                            state.appendStep(step);
                        });
                appendToolResultOnce(run, call, result.summary(), "RESOLVED");
            } catch (AgentExecutionFailureException failure) {
                state.steps(run.id()).stream()
                        .filter(step -> step.id().equals(call.stepId()))
                        .filter(step -> !terminal(step.status()))
                        .findFirst()
                        .ifPresent(step -> {
                            if (step.status() == AgentStepStatus.PENDING) step.start(time.now());
                            step.fail(new AgentStepError(failure.error()), time.now());
                            state.appendStep(step);
                        });
                appendToolResultOnce(run, call, "Tool outcome could not be determined", "OUTCOME_UNKNOWN");
                throw failure;
            }
        }
    }

    private void appendToolResultOnce(AgentRun run, ToolCall call, String summary, String recoveryStatus) {
        boolean exists = state.messages(run.id()).stream()
                .flatMap(message -> message.contents().stream())
                .filter(ToolResultPart.class::isInstance)
                .map(ToolResultPart.class::cast)
                .anyMatch(part -> part.toolCallId().equals(call.id()));
        if (exists) return;
        state.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId(ids.nextValue()),
                run.sessionId(),
                Optional.of(run.id()),
                Optional.empty(),
                MessageRole.TOOL,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolResultPart(call.id(), call.providerCorrelationId(), summary)),
                Map.of("recoveredToolCallId", call.id().value(), "recoveryStatus", recoveryStatus),
                time.now()));
    }

    private boolean needsProjectionRecovery(AgentRun run, ToolCall call) {
        boolean nonTerminalStep = state.steps(run.id()).stream()
                .filter(step -> step.id().equals(call.stepId()))
                .anyMatch(step -> !terminal(step.status()));
        boolean resultMessageMissing = state.messages(run.id()).stream()
                .flatMap(message -> message.contents().stream())
                .filter(ToolResultPart.class::isInstance)
                .map(ToolResultPart.class::cast)
                .noneMatch(part -> part.toolCallId().equals(call.id()));
        return nonTerminalStep || resultMessageMissing;
    }

    private static boolean terminal(AgentStepStatus status) {
        return switch (status) {
            case COMPLETED, FAILED, CANCELLED, SKIPPED -> true;
            default -> false;
        };
    }
}
