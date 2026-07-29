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
        String invocationDigest,
        ExecutionScratchSpaceSpec scratchSpace) {
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
            ExecutionInput input,
            String invocationDigest) {
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
                digestWithScratch(invocationDigest, ExecutionScratchSpaceSpec.genericRequired()),
                ExecutionScratchSpaceSpec.genericRequired());
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
                legacyInvocationDigest(command, workingDirectory, ExecutionScratchSpaceSpec.genericRequired()),
                ExecutionScratchSpaceSpec.genericRequired());
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
                legacyInvocationDigest(command, workingDirectory, ExecutionScratchSpaceSpec.genericRequired()),
                ExecutionScratchSpaceSpec.genericRequired());
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
            SandboxProfileRef sandboxProfileRef,
            ExecutionScratchSpaceSpec scratchSpace) {
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
                legacyInvocationDigest(command, workingDirectory, scratchSpace),
                scratchSpace);
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
        scratchSpace = Objects.requireNonNull(scratchSpace, "scratchSpace must not be null");
        invocationDigest = Objects.requireNonNull(invocationDigest, "invocationDigest must not be null")
                .trim();
        if (!invocationDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invocationDigest must be a lowercase SHA-256 digest");
        }
    }

    public static String digestWithScratch(String invocationDigest, ExecutionScratchSpaceSpec scratchSpace) {
        String base = Objects.requireNonNull(invocationDigest, "invocationDigest must not be null");
        if (!base.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invocationDigest must be a lowercase SHA-256 digest");
        }
        return sha256(base.length() + ":" + base + ";"
                + scratchSpace.canonicalDigest().length() + ":" + scratchSpace.canonicalDigest() + ";");
    }

    private static String legacyInvocationDigest(
            ExecutionCommand command, WorkspacePath workingDirectory, ExecutionScratchSpaceSpec scratchSpace) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        Objects.requireNonNull(scratchSpace, "scratchSpace must not be null");
        String value = command.mode() == ExecutionCommandMode.SHELL
                ? command.shellCommand()
                : String.join("\u0000", command.argv());
        String workdir = workingDirectory.projectPath().toString();
        String scratchDigest = scratchSpace.canonicalDigest();
        String canonical = value.length() + ":" + value + ";" + workdir.length() + ":" + workdir + ";"
                + scratchDigest.length() + ":" + scratchDigest + ";";
        return sha256(canonical);
    }

    private static String sha256(String canonical) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
