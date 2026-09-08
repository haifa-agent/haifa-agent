package io.haifa.agent.application.project.tool;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;
import io.haifa.agent.runtime.core.recovery.ExecutionRecoveryKeys;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxProfile;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Verifies the existing one-shot execution-recovery successor from live Runtime and Interaction state. */
public final class ProjectExecutionRecoveryAuthorization {
    private static final String RECOVERY_TYPE = "execution-recovery";
    private static final String SUCCESSOR_PREFIX = "execution-recovery-tool:v1:";
    private static final Set<String> ELIGIBLE_FAILURE_CODES =
            Set.of("NETWORK_PERMISSION_REQUIRED", "GIT_AUTHENTICATION_UNAVAILABLE", "GH_AUTHENTICATION_UNAVAILABLE");
    private static final Set<SystemGitCliCommandClassifier.Risk> ELIGIBLE_RISKS = Set.of(
            SystemGitCliCommandClassifier.Risk.LOCAL_READ,
            SystemGitCliCommandClassifier.Risk.LOCAL_WRITE,
            SystemGitCliCommandClassifier.Risk.NETWORK_READ,
            SystemGitCliCommandClassifier.Risk.EXTERNAL_WRITE);

    private final RuntimeStateRepository state;
    private final InteractionPort interactions;

    public ProjectExecutionRecoveryAuthorization(RuntimeStateRepository state, InteractionPort interactions) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.interactions = Objects.requireNonNull(interactions, "interactions must not be null");
    }

    public boolean isVerifiedSuccessor(
            AgentRunId runId, ToolCallId successorId, String successorIdempotencyKey, ToolArguments arguments) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(successorId, "successorId must not be null");
        Objects.requireNonNull(successorIdempotencyKey, "successorIdempotencyKey must not be null");
        Objects.requireNonNull(arguments, "arguments must not be null");
        if (!successorId.value().startsWith(SUCCESSOR_PREFIX)) return false;
        List<ToolCall> sources = state.toolCalls(runId).stream()
                .filter(source -> eligibleSource(runId, successorId, successorIdempotencyKey, arguments, source))
                .toList();
        return sources.size() == 1;
    }

    public void requireVerifiedSuccessor(
            AgentRunId runId, ToolCallId successorId, String successorIdempotencyKey, ToolArguments arguments) {
        if (!isVerifiedSuccessor(runId, successorId, successorIdempotencyKey, arguments)) {
            throw new SecurityException("execution recovery correlation is not uniquely verified");
        }
    }

    public static boolean isRecoveryProfileConfigured(SandboxProfile normal, SandboxProfile recovery) {
        Objects.requireNonNull(normal, "normalProfile must not be null");
        Objects.requireNonNull(recovery, "recoveryProfile must not be null");
        return !normal.equals(recovery)
                && recovery.networkPolicy() == NetworkPolicy.ALLOW
                && "host-guarded".equals(recovery.providerId());
    }

    private boolean eligibleSource(
            AgentRunId runId,
            ToolCallId successorId,
            String successorIdempotencyKey,
            ToolArguments arguments,
            ToolCall source) {
        if (source.status() != ToolCallStatus.FAILED
                || !("execution_run".equals(source.toolName()) || "execution.run".equals(source.toolName()))
                || !source.arguments().equals(arguments)) {
            return false;
        }
        Map<String, Object> details =
                source.error().map(value -> value.error().details()).orElse(Map.of());
        Object failureCode = details.get("failureCode");
        if (!(failureCode instanceof String code)
                || !ELIGIBLE_FAILURE_CODES.contains(code)
                || !"NOT_DISPATCHED".equals(details.get("dispatchState"))) {
            return false;
        }
        var requestId = ExecutionRecoveryKeys.requestId(runId, source.id());
        var record = interactions.record(requestId).orElse(null);
        if (record == null
                || record.state() != InteractionState.APPLIED
                || !RECOVERY_TYPE.equals(record.request().type())
                || !record.request().runId().equals(runId)
                || !record.request().approval()
                || record.action().filter(InteractionAction.APPROVE::equals).isEmpty()
                || !(record.request().target() instanceof ToolApprovalTarget target)
                || !target.toolCallId().equals(source.id())) {
            return false;
        }
        var successor = ExecutionRecoveryKeys.successor(runId, source.id(), target.argumentsDigest());
        if (!successor.toolCallId().equals(successorId)
                || !successor.idempotencyKey().value().equals(successorIdempotencyKey)) {
            return false;
        }
        List<ToolCall> persistedSuccessors = state.toolCalls(runId).stream()
                .filter(candidate -> candidate.id().equals(successor.toolCallId()))
                .filter(candidate -> candidate.stepId().equals(successor.stepId()))
                .filter(candidate -> candidate.idempotencyKey().equals(successor.idempotencyKey()))
                .filter(candidate -> candidate.toolName().equals(source.toolName()))
                .filter(candidate -> candidate.toolVersion().equals(source.toolVersion()))
                .filter(candidate -> candidate.arguments().equals(source.arguments()))
                .toList();
        if (persistedSuccessors.size() != 1) return false;
        long successorSteps = state.steps(runId).stream()
                .filter(step -> step.id().equals(successor.stepId()))
                .filter(step ->
                        step.parentStepId().filter(source.stepId()::equals).isPresent())
                .count();
        if (successorSteps != 1) return false;
        Object command = source.arguments().values().get("command");
        if (!(command instanceof String text) || text.isBlank()) return false;
        var classification = SystemGitCliCommandClassifier.classify(text);
        return classification.target() != SystemGitCliCommandClassifier.Target.OTHER
                && ELIGIBLE_RISKS.contains(classification.risk());
    }
}
