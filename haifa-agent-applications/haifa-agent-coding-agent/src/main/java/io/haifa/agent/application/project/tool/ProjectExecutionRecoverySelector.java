package io.haifa.agent.application.project.tool;

import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import java.util.Objects;

/** Product-owned selection of the frozen recovery profile for one verified successor. */
final class ProjectExecutionRecoverySelector {
    private static final String SUCCESSOR_PREFIX = "execution-recovery-tool:v1:";
    private final ProjectExecutionRecoveryAuthorization authorization;
    private final ProjectExecutionToolOperations normal;
    private final ProjectExecutionToolOperations recovery;
    private final SandboxProfile normalProfile;
    private final SandboxProfile recoveryProfile;

    ProjectExecutionRecoverySelector(
            ProjectExecutionRecoveryAuthorization authorization,
            ProjectExecutionToolOperations normal,
            ProjectExecutionToolOperations recovery,
            SandboxProfile normalProfile,
            SandboxProfile recoveryProfile) {
        this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
        this.normal = Objects.requireNonNull(normal, "normal execution must not be null");
        this.recovery = Objects.requireNonNull(recovery, "recovery execution must not be null");
        this.normalProfile = Objects.requireNonNull(normalProfile, "normalProfile must not be null");
        this.recoveryProfile = Objects.requireNonNull(recoveryProfile, "recoveryProfile must not be null");
    }

    ProjectExecutionToolOperations select(ToolInvocationRequest invocation) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        String toolCallId = invocation.toolCallId().value();
        if (!toolCallId.startsWith(SUCCESSOR_PREFIX)) return normal;
        if (!ProjectExecutionRecoveryAuthorization.isRecoveryProfileConfigured(normalProfile, recoveryProfile)) {
            throw new SecurityException("execution recovery profile is unavailable");
        }
        authorization.requireVerifiedSuccessor(
                invocation.runId(),
                invocation.toolCallId(),
                invocation.idempotencyKey().orElse(""),
                invocation.arguments());
        return recovery;
    }
}
