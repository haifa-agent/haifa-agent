package io.haifa.agent.application.project.tool;

import io.haifa.agent.application.project.policy.CodingExecutionRiskResolver;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionInput;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionPreflightException;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
import io.haifa.agent.execution.api.ProcessOutputChunk;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.execution.core.command.CommandSemanticOutcomeInterpreter;
import io.haifa.agent.execution.core.command.SystemGitCliCommandClassifier;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.tool.api.ToolCancellation;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final Duration defaultTimeout;
    private final Duration maximumTimeout;
    private final int maximumModelOutputBytes;
    private final int maximumModelOutputLines;
    private final int maximumProcesses;
    private final ExecutionOutputObserver outputObserver;
    private final UnaryOperator<String> outputSanitizer;
    private final ExecutionScratchSpaceSpec scratchSpace;
    private final UnaryOperator<String> workdirNormalizer;

    public ProjectExecutionToolOperations(
            ExecutionBroker broker,
            IdentifierGenerator identifiers,
            TimeProvider time,
            ExecutionEnvironmentRef environmentRef,
            SandboxProfileRef sandboxProfileRef,
            Duration defaultTimeout,
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
                defaultTimeout,
                maximumTimeout,
                maximumModelOutputBytes,
                maximumModelOutputLines,
                maximumProcesses,
                outputObserver,
                UnaryOperator.identity(),
                ExecutionScratchSpaceSpec.genericRequired(),
                UnaryOperator.identity());
    }

    public ProjectExecutionToolOperations(
            ExecutionBroker broker,
            IdentifierGenerator identifiers,
            TimeProvider time,
            ExecutionEnvironmentRef environmentRef,
            SandboxProfileRef sandboxProfileRef,
            Duration defaultTimeout,
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
                defaultTimeout,
                maximumTimeout,
                maximumModelOutputBytes,
                maximumModelOutputLines,
                maximumProcesses,
                outputObserver,
                outputSanitizer,
                ExecutionScratchSpaceSpec.genericRequired(),
                UnaryOperator.identity());
    }

    public ProjectExecutionToolOperations(
            ExecutionBroker broker,
            IdentifierGenerator identifiers,
            TimeProvider time,
            ExecutionEnvironmentRef environmentRef,
            SandboxProfileRef sandboxProfileRef,
            Duration defaultTimeout,
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
                defaultTimeout,
                maximumTimeout,
                maximumModelOutputBytes,
                maximumModelOutputLines,
                maximumProcesses,
                outputObserver,
                outputSanitizer,
                scratchSpace,
                UnaryOperator.identity());
    }

    public ProjectExecutionToolOperations(
            ExecutionBroker broker,
            IdentifierGenerator identifiers,
            TimeProvider time,
            ExecutionEnvironmentRef environmentRef,
            SandboxProfileRef sandboxProfileRef,
            Duration defaultTimeout,
            Duration maximumTimeout,
            int maximumModelOutputBytes,
            int maximumModelOutputLines,
            int maximumProcesses,
            ExecutionOutputObserver outputObserver,
            UnaryOperator<String> outputSanitizer,
            ExecutionScratchSpaceSpec scratchSpace,
            UnaryOperator<String> workdirNormalizer) {
        this.broker = Objects.requireNonNull(broker, "broker must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.environmentRef = Objects.requireNonNull(environmentRef, "environmentRef must not be null");
        this.sandboxProfileRef = Objects.requireNonNull(sandboxProfileRef, "sandboxProfileRef must not be null");
        this.defaultTimeout = positive(defaultTimeout, "defaultTimeout");
        this.maximumTimeout = positive(maximumTimeout, "maximumTimeout");
        if (defaultTimeout.compareTo(maximumTimeout) > 0) {
            throw new IllegalArgumentException("defaultTimeout exceeds maximumTimeout");
        }
        if (maximumTimeout.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("maximumTimeout exceeds the execution API limit");
        }
        if (maximumModelOutputBytes < 1024 || maximumModelOutputBytes > 1024 * 1024) {
            throw new IllegalArgumentException("maximumModelOutputBytes is out of range");
        }
        if (maximumModelOutputLines < 1 || maximumModelOutputLines > 10_000) {
            throw new IllegalArgumentException("maximumModelOutputLines is out of range");
        }
        if (maximumProcesses < 1 || maximumProcesses > 64) {
            throw new IllegalArgumentException("maximumProcesses is out of range");
        }
        this.maximumModelOutputBytes = maximumModelOutputBytes;
        this.maximumModelOutputLines = maximumModelOutputLines;
        this.maximumProcesses = maximumProcesses;
        this.outputObserver = Objects.requireNonNull(outputObserver, "outputObserver must not be null");
        this.outputSanitizer = Objects.requireNonNull(outputSanitizer, "outputSanitizer must not be null");
        this.scratchSpace = Objects.requireNonNull(scratchSpace, "scratchSpace must not be null");
        this.workdirNormalizer = Objects.requireNonNull(workdirNormalizer, "workdirNormalizer must not be null");
    }

    public ToolResult execute(ToolInvocationRequest invocation, RunWorkspaceAccess access) {
        Objects.requireNonNull(invocation, "invocation must not be null");
        Objects.requireNonNull(access, "access must not be null");
        Map<String, Object> arguments = invocation.arguments().values();
        String command = requiredText(arguments, "command");
        String operationFamily = operationFamily(arguments.get("operationFamily"));
        var commandClassification = SystemGitCliCommandClassifier.classify(command);
        if (commandClassification.risk() == SystemGitCliCommandClassifier.Risk.DENIED) {
            return withToolCallId(invocation, rejectedCommandClassification(operationFamily, commandClassification));
        }
        if (hasLeadingAbsoluteDirectoryChange(command)) {
            return withToolCallId(invocation, rejectedAbsoluteDirectoryChange(operationFamily));
        }
        String requestedWorkdir = optionalText(arguments, "workdir", ".");
        String workdir = Objects.requireNonNull(
                workdirNormalizer.apply(requestedWorkdir), "workdirNormalizer must not return null");
        if (isAbsoluteDirectoryPath(workdir)) {
            return withToolCallId(invocation, rejectedWorkdir(operationFamily, "ABSOLUTE_WORKDIR_FORBIDDEN"));
        }
        Duration requestedTimeout = Duration.ofMillis(
                optionalLong(arguments, "timeoutMillis", defaultTimeout.toMillis(), 1, maximumTimeout.toMillis()));
        Duration remaining = Duration.between(time.now(), invocation.deadline());
        if (remaining.isZero() || remaining.isNegative()) {
            throw new IllegalStateException("tool invocation deadline has expired");
        }
        Duration timeout = requestedTimeout.compareTo(remaining) <= 0 ? requestedTimeout : remaining;
        ExecutionId executionId = new ExecutionId(identifiers.nextValue());
        WorkspacePath workingDirectory;
        try {
            workingDirectory = new WorkspacePath(
                    access.workspaceId(), workdir.equals(".") ? ProjectPath.root() : ProjectPath.of(workdir));
        } catch (IllegalArgumentException exception) {
            return withToolCallId(invocation, rejectedWorkdir(operationFamily, "WORKDIR_INVALID"));
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
                        invocation
                                .policyDecisionRef()
                                .orElseThrow(() ->
                                        new SecurityException("execution tool requires a public policy decision"))),
                access.workspaceId(),
                workingDirectory,
                ExecutionCommand.shell(command),
                environmentRef,
                executionLimits(timeout, operationFamily, commandClassification),
                sandboxProfileRef,
                ExecutionInput.none(),
                invocationDigest(invocation, command, workdir, scratchSpace),
                scratchSpace);
        return withToolCallId(
                invocation,
                executeRequest(
                        request,
                        invocation.cancellation(),
                        invocation.observer(),
                        command,
                        operationFamily,
                        commandClassification));
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

    private ExecutionLimits executionLimits(
            Duration timeout,
            String declaredOperationFamily,
            SystemGitCliCommandClassifier.Classification classification) {
        String budgetFamily = classification.target() == SystemGitCliCommandClassifier.Target.OTHER
                ? declaredOperationFamily
                : effectiveOperationFamily(classification);
        boolean boundedInspection = "INSPECT".equals(budgetFamily);
        int channelBudget = boundedInspection ? maximumModelOutputBytes : FULL_OUTPUT_BYTES_PER_CHANNEL;
        return new ExecutionLimits(
                timeout,
                channelBudget,
                channelBudget,
                maximumProcesses,
                boundedInspection
                        ? io.haifa.agent.execution.api.ExecutionOutputOverflowPolicy.TERMINATE
                        : io.haifa.agent.execution.api.ExecutionOutputOverflowPolicy.RETAIN_HEAD_TAIL);
    }

    private static String invocationDigest(
            ToolInvocationRequest invocation, String command, String workdir, ExecutionScratchSpaceSpec scratchSpace) {
        List<String> fields;
        if (invocation.binding().definition().name().value().equals(ProjectPermissionRequestOperations.TOOL_NAME)) {
            Map<String, Object> arguments = invocation.arguments().values();
            fields = List.of(
                    command,
                    workdir,
                    requiredText(arguments, "priorToolCallId"),
                    requiredText(arguments, "requestedPermission"),
                    requiredText(arguments, "justification"),
                    String.valueOf(arguments.getOrDefault("timeoutMillis", "DEFAULT")));
        } else {
            fields = List.of(command, workdir);
        }
        return ExecutionRequest.digestWithScratch(PolicyDigest.sha256Fields(fields), scratchSpace);
    }

    /**
     * Product-owned user command path. It uses the same broker, policy decision, sandbox, output and
     * audit boundaries as execution.run without manufacturing a model Tool Call.
     */
    public ToolResult executeUserInitiated(
            AgentRunId auditRunId,
            TenantRef tenant,
            PrincipalRef principal,
            RunWorkspaceAccess access,
            String command,
            String workdir,
            Duration timeout,
            String idempotencyKey,
            String policyDecisionRef) {
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
        var commandClassification = SystemGitCliCommandClassifier.classify(command);
        if (commandClassification.risk() == SystemGitCliCommandClassifier.Risk.DENIED) {
            throw new SecurityException(commandClassification.reasonCode());
        }
        ExecutionRequest request = new ExecutionRequest(
                new ExecutionId(identifiers.nextValue()),
                Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null"),
                new TrustedExecutionContext(
                        tenant,
                        auditRunId.value(),
                        principal,
                        access.capabilities(),
                        Objects.requireNonNull(policyDecisionRef, "policyDecisionRef must not be null")),
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
        return executeRequest(
                request, () -> false, ToolInvocationObserver.noop(), command, "UNKNOWN", commandClassification);
    }

    private ToolResult executeRequest(
            ExecutionRequest request,
            ToolCancellation cancellationSignal,
            ToolInvocationObserver invocationObserver,
            String command,
            String operationFamily,
            SystemGitCliCommandClassifier.Classification commandClassification) {
        MergedTailObserver merged = new MergedTailObserver(
                outputObserver, invocationObserver, maximumModelOutputBytes, maximumModelOutputLines);
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
                    commandClassification,
                    sandboxProfileRef,
                    scratchSpace);
        } catch (ExecutionPreflightException exception) {
            throw new ToolInvocationException(
                    exception.code(), ToolDispatchState.NOT_DISPATCHED, exception.getMessage(), exception);
        } catch (io.haifa.agent.execution.core.ExecutionRejectedException exception) {
            throw new ToolInvocationException(
                    exception.code(),
                    merged.dispatched() ? ToolDispatchState.DISPATCHED : ToolDispatchState.NOT_DISPATCHED,
                    exception.getMessage(),
                    exception);
        } catch (io.haifa.agent.sandbox.api.SandboxException exception) {
            throw new ToolInvocationException(
                    exception.code(),
                    merged.dispatched() ? ToolDispatchState.DISPATCHED : ToolDispatchState.NOT_DISPATCHED,
                    exception.getMessage(),
                    exception);
        } finally {
            complete.set(true);
            cancellation.interrupt();
        }
    }

    private static ToolResult toToolResult(
            ExecutionResult result,
            MergedTailObserver merged,
            UnaryOperator<String> outputSanitizer,
            String command,
            String operationFamily,
            SystemGitCliCommandClassifier.Classification commandClassification,
            SandboxProfileRef sandboxProfileRef,
            ExecutionScratchSpaceSpec scratchSpace) {
        var semantic = CommandSemanticOutcomeInterpreter.interpret(command, result.status(), result.exitCode());
        String output = merged.text();
        if (output.isBlank() && !semantic.successfulToolResult()) {
            output = MergedTailObserver.sanitize(fallbackOutput(result));
        }
        output = Objects.requireNonNull(outputSanitizer.apply(output), "outputSanitizer must not return null");
        boolean truncated = merged.truncated()
                || result.stdout().truncated()
                || result.stderr().truncated();
        var data = new LinkedHashMap<String, Object>();
        data.put("executionId", result.id().value());
        data.put("status", result.status().name());
        result.optionalExitCode().ifPresent(value -> data.put("exitCode", value));
        data.put("semanticOutcome", semantic.outcome().name());
        data.put("semanticReasonCode", semantic.reasonCode());
        data.put("semanticInterpreterVersion", CommandSemanticOutcomeInterpreter.VERSION);
        data.put("output", output);
        data.put("truncated", truncated);
        data.put("durationMillis", result.resourceUsage().wallTime().toMillis());
        data.put("operationFamily", operationFamily);
        data.put("declaredOperationFamily", operationFamily);
        data.put("effectiveOperationFamily", effectiveOperationFamily(commandClassification));
        data.put("commandTarget", commandClassification.target().name());
        data.put("commandRisk", commandClassification.risk().name());
        data.put(
                "effectiveRisk",
                CodingExecutionRiskResolver.assess(commandClassification, PolicyRiskLevel.HIGH)
                        .effectiveRisk()
                        .name());
        data.put("commandOperation", commandClassification.operation().name());
        data.put("commandClassificationReason", commandClassification.reasonCode());
        data.put("riskResolverVersion", CodingExecutionRiskResolver.VERSION);
        data.put(
                "sandboxProfileDigest",
                io.haifa.agent.policy.api.PolicyDigest.sha256Fields(
                        List.of(sandboxProfileRef.value(), sandboxProfileRef.version())));
        data.put("scratchSpecDigest", scratchSpace.canonicalDigest());
        data.put("scratchProvisioned", result.scratchProvisioned());
        data.put("scratchCleanupFailed", result.scratchCleanupFailed());
        result.optionalFileChangeSetId().ifPresent(value -> data.put("fileChangeSetId", value.value()));
        if (!semantic.successfulToolResult()) {
            result.optionalFailure().ifPresent(value -> {
                data.put("failureCode", value.code());
                data.put("failureDetail", value.safeDetail());
            });
            var classification = CodingExecutionFailureClassifier.classify(result, output, commandClassification);
            data.put("failureCategory", classification.category());
            data.put("stableFailureCode", classification.stableFailureCode());
            data.put("resourceClass", classification.resourceClass());
            data.put("failureAction", classification.action());
        }
        List<AssetRef> assets = new ArrayList<>();
        result.stdout().optionalAssetRef().ifPresent(assets::add);
        result.stderr().optionalAssetRef().ifPresent(assets::add);
        if (!assets.isEmpty()) {
            data.put("outputRef", assets.getFirst().assetId());
            data.put("outputRefs", assets.stream().map(AssetRef::assetId).toList());
        }
        String headline =
                switch (semantic.outcome()) {
                    case SUCCEEDED -> "Command succeeded";
                    case EXPECTED_VARIANT -> "Command completed with an expected result variant";
                    case EMPTY_RESULT -> "Command completed with an empty result";
                    case COMMAND_FAILED -> "Command failed";
                    case OUTCOME_UNKNOWN ->
                        switch (result.status()) {
                            case OUTPUT_LIMIT_EXCEEDED ->
                                "Command stopped after reaching its output budget; outcome is unknown";
                            case TIMED_OUT -> "Command timed out; outcome is unknown";
                            case CANCELLED -> "Command was cancelled; outcome is unknown";
                            default -> "Command outcome is unknown";
                        };
                };
        if (result.exitCode() != null) headline += " (exit " + result.exitCode() + ")";
        String summaryOutput = output.length() <= SUMMARY_OUTPUT_CHARS
                ? output
                : "<output summary truncated; full bounded head/tail is in result data>\n"
                        + output.substring(0, SUMMARY_OUTPUT_CHARS / 2)
                        + "\n... summary omitted ...\n"
                        + output.substring(output.length() - SUMMARY_OUTPUT_CHARS / 2);
        String summary = summaryOutput.isBlank() ? headline : headline + "\n" + summaryOutput;
        return new ToolResult(
                semantic.successfulToolResult(), summary, Map.copyOf(data), List.copyOf(assets), List.of(), truncated);
    }

    private static ToolResult rejectedAbsoluteDirectoryChange(String operationFamily) {
        return new ToolResult(
                false,
                "Command rejected before execution: absolute directory changes are not allowed; omit cd or use the "
                        + "workspace-relative workdir field.",
                Map.of(
                        "status",
                        "FAILED",
                        "operationFamily",
                        operationFamily,
                        "failureCategory",
                        "INVALID_INPUT",
                        "stableFailureCode",
                        "ABSOLUTE_WORKDIR_FORBIDDEN",
                        "resourceClass",
                        "COMMAND",
                        "failureAction",
                        "Remove the absolute cd and use the workspace-relative workdir field."),
                List.of(),
                List.of(),
                false);
    }

    private static ToolResult rejectedWorkdir(String operationFamily, String stableFailureCode) {
        return new ToolResult(
                false,
                "Command rejected before execution: workdir must be a workspace-relative path.",
                Map.of(
                        "status",
                        "FAILED",
                        "operationFamily",
                        operationFamily,
                        "failureCategory",
                        "INVALID_INPUT",
                        "stableFailureCode",
                        stableFailureCode,
                        "resourceClass",
                        "WORKDIR",
                        "failureAction",
                        "Use a normalized path relative to the authorized workspace root."),
                List.of(),
                List.of(),
                false);
    }

    private static ToolResult rejectedCommandClassification(
            String operationFamily, SystemGitCliCommandClassifier.Classification classification) {
        return new ToolResult(
                false,
                "Command rejected before execution: the command crosses a protected execution boundary.",
                Map.ofEntries(
                        Map.entry("status", "FAILED"),
                        Map.entry("operationFamily", operationFamily),
                        Map.entry("declaredOperationFamily", operationFamily),
                        Map.entry("effectiveOperationFamily", effectiveOperationFamily(classification)),
                        Map.entry("commandTarget", classification.target().name()),
                        Map.entry("commandRisk", classification.risk().name()),
                        Map.entry("commandOperation", classification.operation().name()),
                        Map.entry("commandClassificationReason", classification.reasonCode()),
                        Map.entry("failureCategory", "POLICY"),
                        Map.entry("stableFailureCode", "COMMAND_CLASSIFICATION_REJECTED"),
                        Map.entry("resourceClass", "COMMAND"),
                        Map.entry(
                                "failureAction",
                                "Remove the authentication, executable path, or repository boundary override.")),
                List.of(),
                List.of(),
                false);
    }

    private static String effectiveOperationFamily(SystemGitCliCommandClassifier.Classification classification) {
        return classification.operation().name();
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
        private boolean upstreamTruncated;

        private MergedTailObserver(
                ExecutionOutputObserver delegate,
                ToolInvocationObserver invocationObserver,
                int maximumBytes,
                int maximumLines) {
            this.delegate = delegate;
            this.invocationObserver = invocationObserver;
            output = new io.haifa.agent.execution.api.BoundedOutputBuffer(maximumBytes);
            this.maximumLines = maximumLines;
        }

        @Override
        public void onStarted() {
            if (started.compareAndSet(false, true)) invocationObserver.dispatched();
            try {
                delegate.onStarted();
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
