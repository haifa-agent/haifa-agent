package io.haifa.agent.execution.api;

import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Objects;

public record ExecutionRequest(
        ExecutionId id,
        String idempotencyKey,
        TrustedExecutionContext context,
        WorkspaceId workspaceId,
        WorkspacePath workingDirectory,
        ExecutionCommand command,
        ExecutionEnvironmentRef environmentRef,
        ExecutionLimits limits,
        SandboxProfileRef sandboxProfileRef) {
    public ExecutionRequest {
        id = Objects.requireNonNull(id, "id must not be null");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null")
                .trim();
        if (idempotencyKey.isEmpty()) throw new IllegalArgumentException("idempotencyKey must not be blank");
        context = Objects.requireNonNull(context, "context must not be null");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        if (!workspaceId.equals(workingDirectory.workspaceId())) {
            throw new IllegalArgumentException("working directory belongs to another workspace");
        }
        command = Objects.requireNonNull(command, "command must not be null");
        environmentRef = Objects.requireNonNull(environmentRef, "environmentRef must not be null");
        limits = Objects.requireNonNull(limits, "limits must not be null");
        sandboxProfileRef = Objects.requireNonNull(sandboxProfileRef, "sandboxProfileRef must not be null");
    }
}
