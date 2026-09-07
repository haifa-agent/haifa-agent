package io.haifa.agent.application.project.tool;

import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;
import io.haifa.agent.runtime.core.recovery.ExecutionRecoveryKeys;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Product-owned selection of the frozen recovery profile for one verified successor. */
final class ProjectExecutionRecoverySelector {
    private static final String RECOVERY_TYPE = "execution-recovery";
    private static final String SUCCESSOR_PREFIX = "execution-recovery-tool:v1:";
    private static final Set<String> ELIGIBLE_FAILURE_CODES = Set.of(
            "NETWORK_PERMISSION_REQUIRED", "GIT_AUTHENTICATION_UNAVAILABLE", "GH_AUTHENTICATION_UNAVAILABLE");
    private static final Set<SystemGitCliCommandClassifier.Risk> ELIGIBLE_RISKS = Set.of(
            SystemGitCliCommandClassifier.Risk.LOCAL_READ,
            SystemGitCliCommandClassifier.Risk.LOCAL_WRITE,
            SystemGitCliCommandClassifier.Risk.NETWORK_READ,
            SystemGitCliCommandClassifier.Risk.EXTERNAL_WRITE);

    private final RuntimeStateRepository state;
    private final InteractionPort interactions;
    private final ProjectExecutionToolOperations normal;
    private final ProjectExecutionToolOperations recovery;
    private final SandboxProfile normalProfile;
    private final SandboxProfile recoveryProfile;

    ProjectExecutionRecoverySelector(
            RuntimeStateRepository state,
            InteractionPort interactions,
            ProjectExecutionToolOperations normal,
            ProjectExecutionToolOperations recovery,
            SandboxProfile normalProfile,
            SandboxProfile recoveryProfile) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.interactions = Objects.requireNonNull(interactions, "interactions must not be null");
        this.normal = Objects.requireNonNull(normal, "normal execution must not be null");
        this.recovery = Objects.requireNonNull(recovery, "recovery execution must not be null");
        this.normalProfile = Objects.requireNonNull(normalProfile, "normalProfile must not be null");
        this.recoveryProfile = Objects.requireNonNull(recoveryProfile, "recoveryProfile must not be null");
    }

    ProjectExecutionToolOperations select(ToolInvocationRequest invocation) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        String toolCallId = invocation.toolCallId().value();
        if (!toolCallId.startsWith(SUCCESSOR_PREFIX)) return normal;
        if (!recoveryProfileConfigured()) {
            throw new SecurityException("execution recovery profile is unavailable");
        }
        List<ToolCall> sources = state.toolCalls(invocation.runId()).stream()
                .filter(source -> eligibleSource(invocation, source))
                .toList();
        if (sources.size() != 1) {
            throw new SecurityException("execution recovery correlation is not uniquely verified");
        }
        return recovery;
    }

    private boolean eligibleSource(ToolInvocationRequest invocation, ToolCall source) {
        if (source.status() != ToolCallStatus.FAILED
                || !("execution_run".equals(source.toolName()) || "execution.run".equals(source.toolName()))
                || !source.arguments().equals(invocation.arguments())) {
            return false;
        }
        Map<String, Object> details = source.error().map(value -> value.error().details()).orElse(Map.of());
        Object failureCode = details.get("failureCode");
        if (!(failureCode instanceof String code)
                || !ELIGIBLE_FAILURE_CODES.contains(code)
                || !"NOT_DISPATCHED".equals(details.get("dispatchState"))) {
            return false;
        }
        var requestId = ExecutionRecoveryKeys.requestId(invocation.runId(), source.id());
        var record = interactions.record(requestId).orElse(null);
        if (record == null
                || record.state() != InteractionState.APPLIED
                || !RECOVERY_TYPE.equals(record.request().type())
                || !(record.request().target() instanceof ToolApprovalTarget target)
                || !target.toolCallId().equals(source.id())) {
            return false;
        }
        var successor = ExecutionRecoveryKeys.successor(invocation.runId(), source.id(), target.argumentsDigest());
        if (!successor.toolCallId().equals(invocation.toolCallId())
                || !successor.idempotencyKey().value().equals(invocation.idempotencyKey().orElse(""))) {
            return false;
        }
        Object command = source.arguments().values().get("command");
        if (!(command instanceof String text) || text.isBlank()) return false;
        var classification = SystemGitCliCommandClassifier.classify(text);
        return classification.target() != SystemGitCliCommandClassifier.Target.OTHER
                && ELIGIBLE_RISKS.contains(classification.risk());
    }

    private boolean recoveryProfileConfigured() {
        return !normalProfile.equals(recoveryProfile)
                && recoveryProfile.networkPolicy() == NetworkPolicy.ALLOW
                && "host-guarded".equals(recoveryProfile.providerId());
    }
}
