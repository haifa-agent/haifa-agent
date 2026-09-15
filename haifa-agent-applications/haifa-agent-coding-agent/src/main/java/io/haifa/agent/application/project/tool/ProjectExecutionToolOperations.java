package io.haifa.agent.application.project.tool;

import io.haifa.agent.application.project.product.coding.delivery.CodingValidationAttemptFactory;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfileProvider;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionFailure;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionInput;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionOutput;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionPreflightException;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.ProcessOutputChunk;
import io.haifa.agent.execution.api.ResourceUsageSummary;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.execution.core.command.CredentialEgressGuard;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.tool.api.ToolCancellation;
import io.haifa.agent.tool.api.ToolDispatchEvidence;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolReconciliation;
import io.haifa.agent.tool.api.ToolReconciliationRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/** Adapts the generic project Tool invocation to the single trusted ExecutionBroker path. */
public final class ProjectExecutionToolOperations {
    private static final int FULL_OUTPUT_BYTES_PER_CHANNEL = 16 * 1024 * 1024;
    private static final int SUMMARY_OUTPUT_CHARS = 12 * 1024;

    private final ExecutionBroker broker;
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;
    private final ExecutionEnvironmentRef environmentRef;
    private final SandboxProfileRef sandboxProfileRef;
    private final Duration maximumTimeout;
    private final int maximumModelOutputBytes;
    private final int maximumModelOutputLines;
    private final Optional<Integer> maximumProcesses;
    private final ExecutionOutputObserver outputObserver;
    private final UnaryOperator<String> outputSanitizer;
    private final ExecutionScratchSpaceSpec scratchSpace;
    private final ExecutionWorkspaceTargetResolver workspaceTargets;
    private final CodingVerificationProfileProvider verificationProfiles;

    public ProjectExecutionToolOperations(
            ExecutionBroker broker,
            IdentifierGenerator identifiers,
            TimeProvider time,
            ExecutionEnvironmentRef environmentRef,
            SandboxProfileRef sandboxProfileRef,
            Duration maximumTimeout,
            int maximumModelOutputBytes,
            int maximumModelOutputLines,
            int maximumProcesses,
            ExecutionOutputObserver outputObserver) {
        this(
                broker,
                identifiers,
                time,
                environmentRef,
                sandboxProfileRef,
                maximumTimeout,
                maximumModelOutputBytes,
                maximumModelOutputLines,
                Optional.of(maximumProcesses),
                outputObserver,
                UnaryOperator.identity(),
                ExecutionScratchSpaceSpec.none(),
                ExecutionWorkspaceTargetResolver.currentWorkspaceOnly(),
                CodingVerificationProfileProvider.empty());
    }

    public ProjectExecutionToolOperations(
            ExecutionBroker broker,
            IdentifierGenerator identifiers,
            TimeProvider time,
            ExecutionEnvironmentRef environmentRef,
            SandboxProfileRef sandboxProfileRef,
            Duration maximumTimeout,
            int maximumModelOutputBytes,
            int maximumModelOutputLines,
            int maximumProcesses,
            ExecutionOutputObserver outputObserver,
            UnaryOperator<String> outputSanitizer) {
        this(
                broker,
                identifiers,
                time,
                environmentRef,
                sandboxProfileRef,
                maximumTimeout,
                maximumModelOutputBytes,
                maximumModelOutputLines,
                Optional.of(maximumProcesses),
                outputObserver,
                outputSanitizer,
                ExecutionScratchSpaceSpec.none(),
                ExecutionWorkspaceTargetResolver.currentWorkspaceOnly(),
                CodingVerificationProfileProvider.empty());
    }

    public ProjectExecutionToolOperations(
            ExecutionBroker broker,
            IdentifierGenerator identifiers,
            TimeProvider time,
            ExecutionEnvironmentRef environmentRef,
            SandboxProfileRef sandboxProfileRef,
            Duration maximumTimeout,
            int maximumModelOutputBytes,
            int maximumModelOutputLines,
            int maximumProcesses,
            ExecutionOutputObserver outputObserver,
            UnaryOperator<String> outputSanitizer,
            ExecutionScratchSpaceSpec scratchSpace) {
        this(
                broker,
                identifiers,
                time,
                environmentRef,
                sandboxProfileRef,
                maximumTimeout,
                maximumModelOutputBytes,
                maximumModelOutputLines,
                Optional.of(maximumProcesses),
                outputObserver,
                outputSanitizer,
                scratchSpace,
                ExecutionWorkspaceTargetResolver.currentWorkspaceOnly(),
                CodingVerificationProfileProvider.empty());
    }

    public ProjectExecutionToolOperations(
            ExecutionBroker broker,
            IdentifierGenerator identifiers,
            TimeProvider time,
            ExecutionEnvironmentRef environmentRef,
            SandboxProfileRef sandboxProfileRef,
            Duration maximumTimeout,
            int maximumModelOutputBytes,
            int maximumModelOutputLines,
            int maximumProcesses,
            ExecutionOutputObserver outputObserver,
            UnaryOperator<String> outputSanitizer,
            ExecutionScratchSpaceSpec scratchSpace,
            ExecutionWorkspaceTargetResolver workspaceTargets) {
        this(
                broker,
                identifiers,
                time,
                environmentRef,
                sandboxProfileRef,
                maximumTimeout,
                maximumModelOutputBytes,
                maximumModelOutputLines,
                Optional.of(maximumProcesses),
                outputObserver,
                outputSanitizer,
                scratchSpace,
                workspaceTargets,
                CodingVerificationProfileProvider.empty());
    }

    public ProjectExecutionToolOperations(
            ExecutionBroker broker,
            IdentifierGenerator identifiers,
            TimeProvider time,
            ExecutionEnvironmentRef environmentRef,
            SandboxProfileRef sandboxProfileRef,
            Duration maximumTimeout,
            int maximumModelOutputBytes,
            int maximumModelOutputLines,
            int maximumProcesses,
            ExecutionOutputObserver outputObserver,
            UnaryOperator<String> outputSanitizer,
            ExecutionScratchSpaceSpec scratchSpace,
            ExecutionWorkspaceTargetResolver workspaceTargets,
            CodingVerificationProfileProvider verificationProfiles) {
        this(
                broker,
                identifiers,
                time,
                environmentRef,
                sandboxProfileRef,
                maximumTimeout,
                maximumModelOutputBytes,
                maximumModelOutputLines,
                Optional.of(maximumProcesses),
                outputObserver,
                outputSanitizer,
                scratchSpace,
                workspaceTargets,
                verificationProfiles);
    }

    public ProjectExecutionToolOperations(
            ExecutionBroker broker,
            IdentifierGenerator identifiers,
            TimeProvider time,
            ExecutionEnvironmentRef environmentRef,
            SandboxProfileRef sandboxProfileRef,
            Duration maximumTimeout,
            int maximumModelOutputBytes,
            int maximumModelOutputLines,
            Optional<Integer> maximumProcesses,
            ExecutionOutputObserver outputObserver,
            UnaryOperator<String> outputSanitizer,
            ExecutionScratchSpaceSpec scratchSpace,
            ExecutionWorkspaceTargetResolver workspaceTargets,
            CodingVerificationProfileProvider verificationProfiles) {
        this.broker = Objects.requireNonNull(broker, "broker must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.environmentRef = Objects.requireNonNull(environmentRef, "environmentRef must not be null");
        this.sandboxProfileRef = Objects.requireNonNull(sandboxProfileRef, "sandboxProfileRef must not be null");
        this.maximumTimeout = positive(maximumTimeout, "maximumTimeout");
        if (maximumTimeout.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("maximumTimeout exceeds the execution API limit");
        }
        if (maximumModelOutputBytes < 1024 || maximumModelOutputBytes > 1024 * 1024) {
            throw new IllegalArgumentException("maximumModelOutputBytes is out of range");
        }
        if (maximumModelOutputLines < 1 || maximumModelOutputLines > 10_000) {
            throw new IllegalArgumentException("maximumModelOutputLines is out of range");
        }
        Objects.requireNonNull(maximumProcesses, "maximumProcesses must not be null");
        if (maximumProcesses.isPresent()) {
            int limit = maximumProcesses.get();
            if (limit < 1 || limit > 64) {
                throw new IllegalArgumentException("maximumProcesses is out of range");
            }
        }
        this.maximumModelOutputBytes = maximumModelOutputBytes;
        this.maximumModelOutputLines = maximumModelOutputLines;
        this.maximumProcesses = maximumProcesses;
        this.outputObserver = Objects.requireNonNull(outputObserver, "outputObserver must not be null");
        this.outputSanitizer = Objects.requireNonNull(outputSanitizer, "outputSanitizer must not be null");
        this.scratchSpace = Objects.requireNonNull(scratchSpace, "scratchSpace must not be null");
        this.workspaceTargets = Objects.requireNonNull(workspaceTargets, "workspaceTargets must not be null");
        this.verificationProfiles =
                Objects.requireNonNull(verificationProfiles, "verificationProfiles must not be null");
    }

    public ToolResult execute(ToolInvocationRequest invocation, RunWorkspaceAccess access) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        Objects.requireNonNull(access, "access must not be null");
        Map<String, Object> arguments = invocation.arguments().values();
        String command = requiredText(arguments, "command");
        String operationFamily = operationFamily(arguments.get("operationFamily"));
        var credentialRejection = CredentialEgressGuard.rejectionCode(command);
        if (credentialRejection.isPresent()) {
            return withToolCallId(invocation, rejectedCredentialEgress(operationFamily, credentialRejection.get()));
        }
        if (hasLeadingAbsoluteDirectoryChange(command)) {
            return withToolCallId(invocation, rejectedAbsoluteDirectoryChange(operationFamily));
        }
        String workspaceRef = requiredText(arguments, "workspaceRef");
        String relativeWorkdir = requiredText(arguments, "relativeWorkdir");
        if (isAbsoluteDirectoryPath(relativeWorkdir)) {
            return withToolCallId(invocation, rejectedWorkdir(operationFamily, "ABSOLUTE_WORKDIR_FORBIDDEN"));
        }
        Duration selectedTimeout = Duration.ofMillis(
                optionalLong(arguments, "timeoutMillis", maximumTimeout.toMillis(), 1, maximumTimeout.toMillis()));
        Duration remaining = Duration.between(time.now(), invocation.deadline());
        if (remaining.isZero() || remaining.isNegative() || remaining.toMillis() < 1) {
            throw new IllegalStateException("tool invocation deadline has expired");
        }
        Duration timeout = selectedTimeout.compareTo(remaining) <= 0 ? selectedTimeout : remaining;
        ExecutionId executionId = new ExecutionId(identifiers.nextValue());
        WorkspacePath workingDirectory;
        try {
            workingDirectory = workspaceTargets.resolve(access, workspaceRef, relativeWorkdir);
        } catch (RuntimeException exception) {
            return withToolCallId(invocation, rejectedWorkspaceTarget(operationFamily, exception));
        }
        ExecutionRequest request = new ExecutionRequest(
                executionId,
                invocation
                        .idempotencyKey()
                        .orElseGet(() -> invocation.runId().value() + ":"
                                + invocation.toolCallId().value()),
                new TrustedExecutionContext(
                        invocation.tenant(),
                        invocation.runId().value(),
                        invocation.principal(),
                        access.capabilities(),
                        io.haifa.agent.execution.api.ExecutionOrigin.RUNTIME_TOOL,
                        Optional.of(invocation.toolCallId())),
                workingDirectory.workspaceId(),
                workingDirectory,
                ExecutionCommand.shell(command),
                environmentRef,
                executionLimits(timeout, operationFamily),
                sandboxProfileRef,
                ExecutionInput.none(),
                invocationDigest(command, workspaceRef, relativeWorkdir, scratchSpace),
                scratchSpace);
        return withToolCallId(
                invocation,
                executeRequest(request, invocation.cancellation(), invocation.observer(), command, operationFamily));
    }

    /** Read-only reconciliation for a previously dispatched local execution. */
    public ToolReconciliation reconcile(ToolReconciliationRequest invocation, RunWorkspaceAccess access) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        Objects.requireNonNull(access, "access must not be null");
        Map<String, Object> arguments = invocation.arguments().values();
        String command = requiredText(arguments, "command");
        String operationFamily = operationFamily(arguments.get("operationFamily"));
        String workspaceRef = requiredText(arguments, "workspaceRef");
        String relativeWorkdir = requiredText(arguments, "relativeWorkdir");
        WorkspacePath resolved;
        try {
            resolved = workspaceTargets.resolve(access, workspaceRef, relativeWorkdir);
        } catch (RuntimeException exception) {
            return ToolReconciliation.stillUnknown("WORKSPACE_TARGET_UNAVAILABLE");
        }
        String expectedWorkingDirectoryDigest = workingDirectoryDigest(
                resolved.workspaceId(), resolved.projectPath().toString());
        if (invocation
                .dispatchEvidence()
                .filter(evidence -> !evidence.workingDirectoryDigest().equals(expectedWorkingDirectoryDigest))
                .isPresent()) {
            return ToolReconciliation.stillUnknown("WORKING_DIRECTORY_EVIDENCE_MISMATCH");
        }
        Optional<ExecutionResult> persistedExecution = broker.findByIdempotencyKey(invocation.idempotencyKey());
        if (persistedExecution.isPresent()
                && invocation
                        .dispatchEvidence()
                        .filter(evidence -> !evidence.executionId()
                                .equals(persistedExecution.orElseThrow().id().value()))
                        .isPresent()) {
            return ToolReconciliation.stillUnknown("EXECUTION_ID_EVIDENCE_MISMATCH");
        }
        ToolResult observed = persistedExecution
                .map(result -> {
                    MergedTailObserver reconciliationObserver = new MergedTailObserver(
                            ExecutionOutputObserver.noop(),
                            ToolInvocationObserver.noop(),
                            maximumModelOutputBytes,
                            maximumModelOutputLines,
                            result.id().value(),
                            expectedWorkingDirectoryDigest);
                    if (invocation.dispatchEvidence().isPresent()) {
                        reconciliationObserver.confirmDispatched();
                    }
                    return toToolResult(
                            result,
                            reconciliationObserver,
                            outputSanitizer,
                            command,
                            operationFamily,
                            sandboxProfileRef,
                            scratchSpace,
                            invocation.runId().value());
                })
                .or(() -> invocation.observedResult())
                .orElse(null);
        if (observed == null) return ToolReconciliation.stillUnknown("EXECUTION_RESULT_MISSING");
        Map<String, Object> data = observed.structuredData();
        if (invocation
                .dispatchEvidence()
                .filter(evidence -> data.get("executionId") instanceof String observedExecutionId
                        && !evidence.executionId().equals(observedExecutionId))
                .isPresent()) {
            return ToolReconciliation.stillUnknown("EXECUTION_ID_EVIDENCE_MISMATCH");
        }
        String processState = String.valueOf(data.getOrDefault("processState", "UNKNOWN"));
        if (!processState.equals("UNKNOWN")) {
            return ToolReconciliation.resolved(
                    reconciledResult(observed, "EXECUTION_TERMINAL_AND_WORKSPACE_OBSERVATION_CONFIRMED"),
                    "EXECUTION_TERMINAL_AND_WORKSPACE_OBSERVATION_CONFIRMED");
        }
        return ToolReconciliation.stillUnknown("LOCAL_SIDE_EFFECT_EVIDENCE_MISSING");
    }

    private static ToolResult withToolCallId(ToolInvocationRequest invocation, ToolResult result) {
        var data = new LinkedHashMap<String, Object>(result.structuredData());
        data.put("toolCallId", invocation.toolCallId().value());
        return new ToolResult(
                result.successful(),
                result.summary(),
                Map.copyOf(data),
                result.assets(),
                result.artifacts(),
                result.truncated());
    }

    private ExecutionLimits executionLimits(Duration timeout, String operationFamily) {
        boolean boundedInspection = "INSPECT".equals(operationFamily);
        int channelBudget = outputChannelBudget(operationFamily, maximumModelOutputBytes);
        return new ExecutionLimits(
                timeout,
                channelBudget,
                channelBudget,
                maximumProcesses,
                boundedInspection
                        ? io.haifa.agent.execution.api.ExecutionOutputOverflowPolicy.TERMINATE
                        : io.haifa.agent.execution.api.ExecutionOutputOverflowPolicy.RETAIN_HEAD_TAIL);
    }

    private static int outputChannelBudget(String budgetFamily, int maximumModelOutputBytes) {
        int multiplier =
                switch (budgetFamily) {
                    case "INSPECT" -> 1;
                    case "DIFF" -> 4;
                    case "TEST", "BUILD", "MUTATE", "UNKNOWN" -> 8;
                    default -> 8;
                };
        return Math.min(FULL_OUTPUT_BYTES_PER_CHANNEL, maximumModelOutputBytes * multiplier);
    }

    private static String invocationDigest(
            String command, String workspaceRef, String relativeWorkdir, ExecutionScratchSpaceSpec scratchSpace) {
        return ExecutionRequest.digestWithScratch(
                PolicyDigest.sha256Fields(List.of(command, workspaceRef, relativeWorkdir)), scratchSpace);
    }

    /** Reconstructs the security-relevant request fields produced by this adapter without dispatching. */
    public static void validateFrozenInvocation(
            ToolArguments arguments,
            ExecutionRequest request,
            ExecutionEnvironmentRef environment,
            SandboxProfileRef profile,
            ExecutionScratchSpaceSpec scratchSpace,
            Duration maximumTimeout,
            int maximumModelOutputBytes,
            int maximumProcesses) {
        validateFrozenInvocation(
                arguments,
                request,
                environment,
                profile,
                scratchSpace,
                maximumTimeout,
                maximumModelOutputBytes,
                Optional.of(maximumProcesses));
    }

    /** Reconstructs the security-relevant request fields produced by this adapter without dispatching. */
    public static void validateFrozenInvocation(
            ToolArguments arguments,
            ExecutionRequest request,
            ExecutionEnvironmentRef environment,
            SandboxProfileRef profile,
            ExecutionScratchSpaceSpec scratchSpace,
            Duration maximumTimeout,
            int maximumModelOutputBytes,
            Optional<Integer> maximumProcesses) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(maximumTimeout, "maximumTimeout must not be null");
        Map<String, Object> values = arguments.values();
        String command = requiredText(values, "command");
        String workspaceRef = requiredText(values, "workspaceRef");
        String relativeWorkdir = requiredText(values, "relativeWorkdir");
        String declaredOperationFamily = operationFamily(values.get("operationFamily"));
        if (CredentialEgressGuard.rejects(command)
                || hasLeadingAbsoluteDirectoryChange(command)
                || isAbsoluteDirectoryPath(relativeWorkdir)) {
            throw new SecurityException("canonical execution command or workdir is denied");
        }
        Duration requestedTimeout = Duration.ofMillis(
                optionalLong(values, "timeoutMillis", maximumTimeout.toMillis(), 1, maximumTimeout.toMillis()));
        boolean boundedInspection = "INSPECT".equals(declaredOperationFamily);
        int channelBudget = outputChannelBudget(declaredOperationFamily, maximumModelOutputBytes);
        String expectedDigest = ExecutionRequest.digestWithScratch(
                PolicyDigest.sha256Fields(List.of(command, workspaceRef, relativeWorkdir)), scratchSpace);
        if (!request.workspaceId().value().equals(workspaceRef)
                || !request.workingDirectory().projectPath().toString().equals(relativeWorkdir)
                || !request.command().equals(ExecutionCommand.shell(command))
                || !request.environmentRef().equals(environment)
                || !request.sandboxProfileRef().equals(profile)
                || !request.input().equals(ExecutionInput.none())
                || !request.scratchSpace().equals(scratchSpace)
                || !request.invocationDigest().equals(expectedDigest)
                || request.limits().timeout().compareTo(requestedTimeout) > 0
                || request.limits().timeout().compareTo(maximumTimeout) > 0
                || request.limits().maxStdoutBytes() != channelBudget
                || request.limits().maxStderrBytes() != channelBudget
                || !Objects.equals(request.limits().maxProcesses(), maximumProcesses)
                || request.limits().outputOverflowPolicy()
                        != (boundedInspection
                                ? io.haifa.agent.execution.api.ExecutionOutputOverflowPolicy.TERMINATE
                                : io.haifa.agent.execution.api.ExecutionOutputOverflowPolicy.RETAIN_HEAD_TAIL)) {
            throw new SecurityException("execution request drifted from the frozen Coding Tool invocation");
        }
    }

    /**
     * Product-owned user command path. It uses the same broker, policy decision, sandbox, output and
     * audit boundaries as execution_run without manufacturing a model Tool Call.
     */
    public ToolResult executeUserInitiated(
            AgentRunId auditRunId,
            TenantRef tenant,
            PrincipalRef principal,
            RunWorkspaceAccess access,
            String command,
            String workdir,
            Duration timeout,
            String idempotencyKey) {
        Objects.requireNonNull(auditRunId, "auditRunId must not be null");
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(access, "access must not be null");
        command = requiredText(Map.of("command", command), "command");
        workdir = optionalText(Map.of("workdir", workdir), "workdir", ".");
        timeout = positive(timeout, "timeout");
        if (timeout.compareTo(maximumTimeout) > 0) {
            throw new IllegalArgumentException("timeout exceeds maximumTimeout");
        }
        CredentialEgressGuard.rejectionCode(command).ifPresent(code -> {
            throw new SecurityException(code);
        });
        ExecutionRequest request = new ExecutionRequest(
                new ExecutionId(identifiers.nextValue()),
                Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null"),
                new TrustedExecutionContext(
                        tenant,
                        auditRunId.value(),
                        principal,
                        access.capabilities(),
                        io.haifa.agent.execution.api.ExecutionOrigin.PRODUCT_USER_COMMAND,
                        Optional.empty()),
                access.workspaceId(),
                new WorkspacePath(
                        access.workspaceId(), workdir.equals(".") ? ProjectPath.root() : ProjectPath.of(workdir)),
                ExecutionCommand.shell(command),
                environmentRef,
                new ExecutionLimits(
                        timeout, FULL_OUTPUT_BYTES_PER_CHANNEL, FULL_OUTPUT_BYTES_PER_CHANNEL, maximumProcesses),
                sandboxProfileRef,
                ExecutionInput.none(),
                ExecutionRequest.digestWithScratch(PolicyDigest.sha256Fields(List.of(command, workdir)), scratchSpace),
                scratchSpace);
        return executeRequest(request, () -> false, ToolInvocationObserver.noop(), command, "UNKNOWN");
    }

    private ToolResult executeRequest(
            ExecutionRequest request,
            ToolCancellation cancellationSignal,
            ToolInvocationObserver invocationObserver,
            String command,
            String operationFamily) {
        MergedTailObserver merged = new MergedTailObserver(
                outputObserver,
                invocationObserver,
                maximumModelOutputBytes,
                maximumModelOutputLines,
                request.id().value(),
                workingDirectoryDigest(
                        request.workspaceId(),
                        request.workingDirectory().projectPath().toString()));
        AtomicBoolean complete = new AtomicBoolean();
        Thread cancellation = Thread.ofVirtual()
                .name("haifa-execution-cancellation")
                .start(() -> {
                    while (!complete.get()) {
                        if (cancellationSignal.isCancellationRequested()) {
                            if (broker.cancel(request.id())) return;
                        }
                        try {
                            Thread.sleep(25);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                    }
                });
        try {
            ExecutionResult result = broker.execute(request, merged);
            if (merged.dispatched()) invocationObserver.acknowledged();
            return toToolResult(
                    result,
                    merged,
                    outputSanitizer,
                    command,
                    operationFamily,
                    sandboxProfileRef,
                    scratchSpace,
                    request.context().runRef());
        } catch (ExecutionPreflightException exception) {
            return toFailedToolResult(
                    request, merged, exception.code(), exception.getMessage(), command, operationFamily);
        } catch (io.haifa.agent.execution.core.ExecutionRejectedException exception) {
            return toFailedToolResult(
                    request, merged, exception.code(), exception.getMessage(), command, operationFamily);
        } catch (io.haifa.agent.sandbox.api.SandboxException exception) {
            return toFailedToolResult(
                    request, merged, exception.code(), exception.getMessage(), command, operationFamily);
        } catch (RuntimeException exception) {
            return toFailedToolResult(
                    request,
                    merged,
                    "EXECUTION_FAILED",
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(),
                    command,
                    operationFamily);
        } finally {
            complete.set(true);
            cancellation.interrupt();
        }
    }

    private ToolResult toFailedToolResult(
            ExecutionRequest request,
            MergedTailObserver merged,
            String failureCode,
            String errorMessage,
            String command,
            String operationFamily) {
        String safeError = errorMessage == null || errorMessage.isBlank() ? "execution failed" : errorMessage;
        byte[] errBytes = safeError.getBytes(StandardCharsets.UTF_8);
        String safeDigestError = safeError.length() > 16_000 ? safeError.substring(0, 16_000) : safeError;
        String errDigest = PolicyDigest.sha256Fields(List.of(safeDigestError));
        ExecutionOutput errOutput = new ExecutionOutput(safeError, null, errBytes.length, errDigest, false, false);
        ExecutionOutput outOutput = new ExecutionOutput("", null, 0, "0".repeat(64), false, false);
        Instant now = time.now();
        ExecutionResult failureResult = new ExecutionResult(
                request.id(),
                ExecutionStatus.FAILED,
                null,
                now,
                now,
                outOutput,
                errOutput,
                sandboxProfileRef.value(),
                new ResourceUsageSummary(Duration.ZERO, 0),
                new ExecutionFailure(failureCode, safeError),
                false,
                false,
                false);
        return toToolResult(
                failureResult,
                merged,
                outputSanitizer,
                command,
                operationFamily,
                sandboxProfileRef,
                scratchSpace,
                request.context().runRef());
    }

    private ToolResult toToolResult(
            ExecutionResult result,
            MergedTailObserver merged,
            UnaryOperator<String> outputSanitizer,
            String command,
            String operationFamily,
            SandboxProfileRef sandboxProfileRef,
            ExecutionScratchSpaceSpec scratchSpace,
            String runRef) {
        boolean exited = result.status() == ExecutionStatus.EXITED;
        String output = merged.text();
        if (output.isBlank() && !exited) {
            String fallback = fallbackOutput(result);
            if (fallback.isBlank()) {
                fallback = switch (result.status()) {
                    case TIMED_OUT -> "Command timed out after execution limit.";
                    case OUTPUT_LIMIT_EXCEEDED -> "Command exceeded output limit.";
                    case PROCESS_LIMIT_EXCEEDED -> "Command exceeded process-count limit.";
                    default -> "Command execution failed with no output.";
                };
            }
            output = MergedTailObserver.sanitize(fallback);
        }
        if (!exited
                && result.status() == ExecutionStatus.TIMED_OUT
                && !output.toLowerCase(java.util.Locale.ROOT).contains("timed out")) {
            output = output.isBlank() ? "Command timed out." : output + "\n\n[Command timed out before completion]";
        }
        output = Objects.requireNonNull(outputSanitizer.apply(output), "outputSanitizer must not return null");
        boolean truncated = merged.truncated()
                || result.stdout().truncated()
                || result.stderr().truncated();
        var data = new LinkedHashMap<String, Object>();
        data.put("executionId", result.id().value());
        data.put("processState", result.status().name());
        result.optionalExitCode().ifPresent(value -> data.put("exitCode", value));
        if (result.status() == ExecutionStatus.UNKNOWN) {
            data.put("runtimeOutcome", "OUTCOME_UNKNOWN");
        }
        data.put("output", output);
        data.put("truncated", truncated);
        data.put("outputIncomplete", result.outputIncomplete());
        data.put("durationMillis", result.resourceUsage().wallTime().toMillis());
        data.put("observedProcessCount", result.resourceUsage().observedProcessCount());
        data.put("operationFamily", operationFamily);
        data.put("outputBudgetFamily", operationFamily);
        data.put("outputBudgetBytesPerChannel", outputChannelBudget(operationFamily, maximumModelOutputBytes));
        data.put("modelOutputBudgetBytes", maximumModelOutputBytes);
        data.put("modelOutputBudgetLines", maximumModelOutputLines);
        data.put(
                "sandboxProfileDigest",
                io.haifa.agent.policy.api.PolicyDigest.sha256Fields(
                        List.of(sandboxProfileRef.value(), sandboxProfileRef.version())));
        if (scratchSpace.isPresent()) {
            data.put("scratchSpecDigest", scratchSpace.canonicalDigest());
            data.put("scratchProvisioned", result.scratchProvisioned());
            data.put("scratchCleanupFailed", result.scratchCleanupFailed());
        }
        if (merged.dispatched()) {
            CodingValidationAttemptFactory.create(
                            command, verificationProfiles.configurationFor(new AgentRunId(runRef)))
                    .ifPresent(evidence -> {
                        data.put("validationEvidence", evidence.toStructuredData());
                        data.put(
                                "validationAttemptRef",
                                PolicyDigest.sha256Fields(List.of(
                                        evidence.schemaVersion(),
                                        evidence.verificationProfileDigest(),
                                        evidence.verificationCandidateDigest(),
                                        evidence.claimCode())));
                    });
        }
        if (!exited) {
            result.optionalFailure().ifPresent(value -> {
                data.put("failureCode", value.code());
                data.put("failureDetail", value.safeDetail());
            });
            var classification = CodingExecutionFailureClassifier.classify(result, output);
            data.put("failureCategory", classification.category());
            data.put("stableFailureCode", classification.stableFailureCode());
            data.put("resourceClass", classification.resourceClass());
            data.put(
                    "failureActionCode",
                    failureActionCode(classification.category(), classification.stableFailureCode()));
            data.put("failureAction", classification.action());
        }
        List<AssetRef> assets = new ArrayList<>();
        result.stdout().optionalAssetRef().ifPresent(assets::add);
        result.stderr().optionalAssetRef().ifPresent(assets::add);
        if (!assets.isEmpty()) {
            data.put("outputRef", assets.getFirst().assetId());
            data.put("outputRefs", assets.stream().map(AssetRef::assetId).toList());
        }
        if (operationFamily.equals("DIFF")) {
            long files = output.lines()
                    .filter(line -> line.startsWith("diff --git "))
                    .count();
            long hunks = output.lines().filter(line -> line.startsWith("@@")).count();
            data.put("diffFileCount", files);
            data.put("diffHunkCount", hunks);
            data.put("diffCountsComplete", !truncated);
            data.put(
                    "diffSummary",
                    "observedFiles=" + files + ", observedHunks=" + hunks + ", countsComplete=" + !truncated);
            if (!assets.isEmpty()) data.put("diffArtifactRef", assets.getFirst().assetId());
        }
        String headline =
                switch (result.status()) {
                    case EXITED -> "Command exited";
                    case FAILED -> "Command failed";
                    case OUTPUT_LIMIT_EXCEEDED ->
                        "Command stopped after reaching its output budget; outcome is unknown";
                    case PROCESS_LIMIT_EXCEEDED -> "Command stopped after reaching its process-count budget";
                    case TIMED_OUT -> "Command timed out";
                    case CANCELLED -> "Command was cancelled";
                    case UNKNOWN -> "Command outcome is unknown";
                };
        if (result.exitCode() != null) headline += " (exit " + result.exitCode() + ")";
        String summary;
        if (operationFamily.equals("DIFF")) {
            summary = headline + "\n" + data.get("diffSummary");
            if (data.containsKey("diffArtifactRef")) summary += ", artifactRef=" + data.get("diffArtifactRef");
        } else {
            String summaryOutput = output.length() <= SUMMARY_OUTPUT_CHARS
                    ? output
                    : "<output summary truncated; full bounded head/tail is in result data>\n"
                            + output.substring(0, SUMMARY_OUTPUT_CHARS / 2)
                            + "\n... summary omitted ...\n"
                            + output.substring(output.length() - SUMMARY_OUTPUT_CHARS / 2);
            summary = summaryOutput.isBlank() ? headline : headline + "\n" + summaryOutput;
        }
        return new ToolResult(exited, summary, Map.copyOf(data), List.copyOf(assets), List.of(), truncated);
    }

    private static ToolResult rejectedAbsoluteDirectoryChange(String operationFamily) {
        return new ToolResult(
                false,
                "Command rejected before execution: absolute directory changes are not allowed; omit cd or use "
                        + "workspaceRef with relativeWorkdir.",
                Map.of(
                        "processState",
                        "FAILED",
                        "operationFamily",
                        operationFamily,
                        "failureCategory",
                        "INVALID_INPUT",
                        "stableFailureCode",
                        "ABSOLUTE_WORKDIR_FORBIDDEN",
                        "resourceClass",
                        "COMMAND",
                        "failureActionCode",
                        "USE_WORKSPACE_RELATIVE_WORKDIR",
                        "failureAction",
                        "Remove the absolute cd and provide workspaceRef with relativeWorkdir."),
                List.of(),
                List.of(),
                false);
    }

    private static String workingDirectoryDigest(
            io.haifa.agent.project.workspace.WorkspaceId workspaceId, String workdir) {
        return PolicyDigest.sha256Fields(List.of("execution-working-directory-v1", workspaceId.value(), workdir));
    }

    private static ToolResult reconciledResult(ToolResult observed, String reasonCode) {
        var data = new LinkedHashMap<String, Object>(observed.structuredData());
        data.remove("runtimeOutcome");
        data.put("reconcileStatus", "RESOLVED");
        data.put("reconcileReason", reasonCode);
        data.put("replayAllowed", false);
        return new ToolResult(
                observed.successful(),
                "Reconciled without replay: " + observed.summary(),
                Map.copyOf(data),
                observed.assets(),
                observed.artifacts(),
                observed.truncated());
    }

    private static ToolResult rejectedWorkdir(String operationFamily, String stableFailureCode) {
        return new ToolResult(
                false,
                "Command rejected before execution: relativeWorkdir must be a normalized workspace-relative path.",
                Map.of(
                        "processState",
                        "FAILED",
                        "operationFamily",
                        operationFamily,
                        "failureCategory",
                        "INVALID_INPUT",
                        "stableFailureCode",
                        stableFailureCode,
                        "resourceClass",
                        "WORKDIR",
                        "failureActionCode",
                        "USE_WORKSPACE_RELATIVE_WORKDIR",
                        "failureAction",
                        "Use workspaceRef with a normalized relativeWorkdir below that authorized root."),
                List.of(),
                List.of(),
                false);
    }

    private static ToolResult rejectedWorkspaceTarget(String operationFamily, RuntimeException failure) {
        String stableCode = failure
                                instanceof io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScopeException scope
                        && scope.code()
                                == io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScopeErrorCode.ACCESS_DENIED
                ? "WORKSPACE_REF_UNAVAILABLE"
                : "WORKDIR_INVALID";
        return rejectedWorkdir(operationFamily, stableCode);
    }

    private static ToolResult rejectedCredentialEgress(String operationFamily, String reasonCode) {
        return new ToolResult(
                false,
                "Command rejected before execution: the command may expose or override host authentication material.",
                Map.of(
                        "processState", "FAILED",
                        "operationFamily", operationFamily,
                        "failureCategory", "POLICY",
                        "stableFailureCode", "AUTHENTICATION_OVERRIDE_DENIED",
                        "resourceClass", "AUTHENTICATION",
                        "credentialBoundaryCode", reasonCode,
                        "failureActionCode", "REMOVE_AUTHENTICATION_OVERRIDE",
                        "failureAction",
                                "Remove the host authentication override; use the managed Credential Lease path."),
                List.of(),
                List.of(),
                false);
    }

    private static String failureActionCode(String category, String stableCode) {
        return switch (stableCode) {
            case "AUTHENTICATION_OVERRIDE_DENIED" -> "REMOVE_AUTHENTICATION_OVERRIDE";
            case "DEPENDENCY_UNAVAILABLE" -> "RESTORE_TOOLCHAIN_OR_USE_EQUIVALENT";
            case "CANCELLED" -> "DO_NOT_AUTOMATICALLY_RETRY";
            case "TIMEOUT", "OUTCOME_UNKNOWN", "OUTPUT_LIMIT_EXCEEDED", "PROCESS_LIMIT_EXCEEDED" ->
                "VERIFY_OUTCOME_BEFORE_RETRY";
            default ->
                switch (category) {
                    case "DEPENDENCY_UNAVAILABLE" -> "RESTORE_TOOLCHAIN_OR_USE_EQUIVALENT";
                    case "COMMAND_FAILED" -> "CONTINUE_WITH_DIAGNOSTIC";
                    case "OUTCOME_UNKNOWN" -> "VERIFY_OUTCOME_BEFORE_RETRY";
                    default -> "REVIEW_BOUNDED_FAILURE";
                };
        };
    }

    private static boolean hasLeadingAbsoluteDirectoryChange(String command) {
        String remaining = command.stripLeading();
        if (!remaining.startsWith("cd") || (remaining.length() > 2 && !Character.isWhitespace(remaining.charAt(2)))) {
            return false;
        }
        remaining = remaining.substring(2).stripLeading();
        if (remaining.startsWith("--") && (remaining.length() == 2 || Character.isWhitespace(remaining.charAt(2)))) {
            remaining = remaining.substring(2).stripLeading();
        }
        if (remaining.isEmpty()) return false;
        char quote = remaining.charAt(0);
        String path = remaining;
        if (quote == '\'' || quote == '"') {
            path = remaining.substring(1);
        }
        return isAbsoluteDirectoryPath(path);
    }

    private static boolean isAbsoluteDirectoryPath(String path) {
        return path.startsWith("/")
                || path.startsWith("\\\\")
                || path.startsWith("~/")
                || (path.length() >= 3
                        && Character.isLetter(path.charAt(0))
                        && path.charAt(1) == ':'
                        && (path.charAt(2) == '\\' || path.charAt(2) == '/'));
    }

    private static String fallbackOutput(ExecutionResult result) {
        String stdout = result.stdout().summary();
        String stderr = result.stderr().summary();
        if (stdout.isBlank()) return stderr;
        if (stderr.isBlank()) return stdout;
        return stdout + "\n" + stderr;
    }

    private static String requiredText(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " must be non-empty text");
        }
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException(key + " contains NUL");
        return text;
    }

    private static String optionalText(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " must be non-empty text");
        }
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException(key + " contains NUL");
        return text;
    }

    private static long optionalLong(
            Map<String, Object> values, String key, long fallback, long minimum, long maximum) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be a number");
        long result = number.longValue();
        if (result < minimum || result > maximum) throw new IllegalArgumentException(key + " is out of range");
        return result;
    }

    private static String operationFamily(Object value) {
        if (value == null) return "UNKNOWN";
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("operationFamily must be text");
        }
        String normalized = text.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("BUILD", "TEST", "DIFF", "INSPECT", "MUTATE", "UNKNOWN").contains(normalized)) {
            throw new IllegalArgumentException("operationFamily is unsupported");
        }
        return normalized;
    }

    private static Duration positive(Duration value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

    private static final class MergedTailObserver implements ExecutionOutputObserver {
        private final ExecutionOutputObserver delegate;
        private final ToolInvocationObserver invocationObserver;
        private final AtomicBoolean started = new AtomicBoolean();
        private final io.haifa.agent.execution.api.BoundedOutputBuffer output;
        private final int maximumLines;
        private final String executionId;
        private final String workingDirectoryDigest;
        private boolean upstreamTruncated;

        private MergedTailObserver(
                ExecutionOutputObserver delegate,
                ToolInvocationObserver invocationObserver,
                int maximumBytes,
                int maximumLines,
                String executionId,
                String workingDirectoryDigest) {
            this.delegate = delegate;
            this.invocationObserver = invocationObserver;
            output = new io.haifa.agent.execution.api.BoundedOutputBuffer(maximumBytes);
            this.maximumLines = maximumLines;
            this.executionId = executionId;
            this.workingDirectoryDigest = workingDirectoryDigest;
        }

        @Override
        public void onStarted() {
            if (started.compareAndSet(false, true)) {
                invocationObserver.dispatched(
                        new ToolDispatchEvidence(executionId, java.util.OptionalLong.empty(), workingDirectoryDigest));
            }
            try {
                delegate.onStarted();
            } catch (RuntimeException ignored) {
                // CLI rendering errors cannot change the authoritative dispatch boundary.
            }
        }

        @Override
        public void onStarted(io.haifa.agent.execution.api.ExecutionProcessIdentity identity) {
            if (started.compareAndSet(false, true)) {
                invocationObserver.dispatched(new ToolDispatchEvidence(
                        executionId, java.util.OptionalLong.of(identity.processId()), workingDirectoryDigest));
            }
            try {
                delegate.onStarted(identity);
            } catch (RuntimeException ignored) {
                // CLI rendering errors cannot change the authoritative dispatch boundary.
            }
        }

        @Override
        public synchronized void onOutput(ProcessOutputChunk chunk) {
            upstreamTruncated |= chunk.truncated();
            try {
                delegate.onOutput(chunk);
            } catch (RuntimeException ignored) {
                // CLI rendering errors cannot remove output from the authoritative Tool result.
            }
            output.write(chunk.bytes());
        }

        private synchronized String text() {
            return keepHeadAndTailLines(sanitize(new String(output.bytes(), StandardCharsets.UTF_8)), maximumLines);
        }

        private synchronized boolean truncated() {
            String retained = sanitize(new String(output.bytes(), StandardCharsets.UTF_8));
            return upstreamTruncated || output.truncated() || lineCount(retained) > maximumLines;
        }

        private boolean dispatched() {
            return started.get();
        }

        private void confirmDispatched() {
            started.set(true);
        }

        private static String keepHeadAndTailLines(String value, int maximumLines) {
            String[] lines = value.split("(?<=\\n)");
            if (lines.length <= maximumLines) return value;
            int head = (maximumLines + 1) / 2;
            int tail = maximumLines - head;
            StringBuilder bounded = new StringBuilder(value.length());
            for (int index = 0; index < head; index++) bounded.append(lines[index]);
            bounded.append("... ").append(lines.length - maximumLines).append(" lines omitted ...\n");
            for (int index = lines.length - tail; index < lines.length; index++) bounded.append(lines[index]);
            return bounded.toString();
        }

        private static long lineCount(String value) {
            if (value.isEmpty()) return 0;
            long breaks = value.chars().filter(character -> character == '\n').count();
            return breaks + (value.endsWith("\n") ? 0 : 1);
        }

        private static String sanitize(String value) {
            String withoutAnsi = value.replaceAll("\\u001B\\[[;?0-9]*[ -/]*[@-~]", "");
            StringBuilder safe = new StringBuilder(withoutAnsi.length());
            withoutAnsi.codePoints().forEach(codePoint -> {
                if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t' || !Character.isISOControl(codePoint)) {
                    safe.appendCodePoint(codePoint);
                }
            });
            return safe.toString();
        }
    }
}
