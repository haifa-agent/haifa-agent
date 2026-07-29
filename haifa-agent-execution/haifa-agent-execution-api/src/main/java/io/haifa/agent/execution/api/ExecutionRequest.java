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
        SandboxProfileRef sandboxProfileRef,
        ExecutionInput input,
        String invocationDigest) {
    public ExecutionRequest(
            ExecutionId id,
            String idempotencyKey,
            TrustedExecutionContext context,
            WorkspaceId workspaceId,
            WorkspacePath workingDirectory,
            ExecutionCommand command,
            ExecutionEnvironmentRef environmentRef,
            ExecutionLimits limits,
            SandboxProfileRef sandboxProfileRef,
            ExecutionInput input) {
        this(
                id,
                idempotencyKey,
                context,
                workspaceId,
                workingDirectory,
                command,
                environmentRef,
                limits,
                sandboxProfileRef,
                input,
                legacyInvocationDigest(command, workingDirectory));
    }

    public ExecutionRequest(
            ExecutionId id,
            String idempotencyKey,
            TrustedExecutionContext context,
            WorkspaceId workspaceId,
            WorkspacePath workingDirectory,
            ExecutionCommand command,
            ExecutionEnvironmentRef environmentRef,
            ExecutionLimits limits,
            SandboxProfileRef sandboxProfileRef) {
        this(
                id,
                idempotencyKey,
                context,
                workspaceId,
                workingDirectory,
                command,
                environmentRef,
                limits,
                sandboxProfileRef,
                ExecutionInput.none(),
                legacyInvocationDigest(command, workingDirectory));
    }

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
        input = Objects.requireNonNull(input, "input must not be null");
        invocationDigest = Objects.requireNonNull(invocationDigest, "invocationDigest must not be null")
                .trim();
        if (!invocationDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invocationDigest must be a lowercase SHA-256 digest");
        }
    }

    private static String legacyInvocationDigest(ExecutionCommand command, WorkspacePath workingDirectory) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        String value = command.mode() == ExecutionCommandMode.SHELL
                ? command.shellCommand()
                : String.join("\u0000", command.argv());
        String workdir = workingDirectory.projectPath().toString();
        String canonical = value.length() + ":" + value + ";" + workdir.length() + ":" + workdir + ";";
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
