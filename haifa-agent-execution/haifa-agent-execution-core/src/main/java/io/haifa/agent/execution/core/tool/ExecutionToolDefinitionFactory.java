package io.haifa.agent.execution.core.tool;

import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Canonical v3 model-visible definition for command and script execution. */
public final class ExecutionToolDefinitionFactory {
    public static final ToolAlias ALIAS = new ToolAlias("execution_run");
    /**
     * Declared maximum timeout of the default product definition: the Personal Assistant value. A
     * product that owns a longer reference Tool timeout declares its own maximum instead.
     */
    public static final Duration DEFAULT_MAXIMUM_TIMEOUT = Duration.ofSeconds(30);

    private ExecutionToolDefinitionFactory() {}

    public static ToolDefinition create(
            String executionProfileIdentity,
            boolean networkAllowed,
            boolean workingDirectoryAllowed,
            Set<String> scriptLanguages) {
        return create(
                executionProfileIdentity,
                executionProfileIdentity,
                ExecutionScratchSpaceSpec.none().canonicalDigest(),
                networkAllowed,
                workingDirectoryAllowed,
                scriptLanguages);
    }

    public static ToolDefinition create(
            String executionProfileIdentity,
            String configurationIdentity,
            boolean networkAllowed,
            boolean workingDirectoryAllowed,
            Set<String> scriptLanguages) {
        return create(
                executionProfileIdentity,
                configurationIdentity,
                ExecutionScratchSpaceSpec.none().canonicalDigest(),
                networkAllowed,
                workingDirectoryAllowed,
                scriptLanguages);
    }

    public static ToolDefinition create(
            String executionProfileIdentity,
            String configurationIdentity,
            String scratchSpecDigest,
            boolean networkAllowed,
            boolean workingDirectoryAllowed,
            Set<String> scriptLanguages) {
        return create(
                executionProfileIdentity,
                configurationIdentity,
                scratchSpecDigest,
                DEFAULT_MAXIMUM_TIMEOUT,
                networkAllowed,
                workingDirectoryAllowed,
                scriptLanguages);
    }

    /**
     * Canonical definition whose model-visible timeout bound is declared by the product.
     *
     * <p>The declared maximum is a product definition, not a shared SDK policy: it becomes both the
     * Tool's own timeout and the {@code timeoutMillis} maximum of the model-visible input schema.
     * Passing {@link #DEFAULT_MAXIMUM_TIMEOUT} reproduces the default definition exactly, so a
     * shorter product definition such as the Personal Assistant's keeps its frozen schema.
     *
     * @param maximumTimeout declared maximum the model may request; at least one second and at most
     *     {@link ExecutionToolConfiguration#MAXIMUM_DECLARED_TIMEOUT}
     */
    public static ToolDefinition create(
            String executionProfileIdentity,
            String configurationIdentity,
            String scratchSpecDigest,
            Duration maximumTimeout,
            boolean networkAllowed,
            boolean workingDirectoryAllowed,
            Set<String> scriptLanguages) {
        Objects.requireNonNull(maximumTimeout, "maximumTimeout must not be null");
        if (maximumTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || maximumTimeout.compareTo(ExecutionToolConfiguration.MAXIMUM_DECLARED_TIMEOUT) > 0) {
            throw new IllegalArgumentException("maximumTimeout is out of range");
        }
        Set<ToolSideEffect> effects = networkAllowed
                ? Set.of(ToolSideEffect.PROCESS_EXECUTION, ToolSideEffect.NETWORK_ACCESS)
                : Set.of(ToolSideEffect.PROCESS_EXECUTION);
        return new ToolDefinition(
                new ToolName("execution_run"),
                new SemanticVersion("3.0.0"),
                ExecutionToolProvider.PROVIDER_ID,
                "Run an approved command or script",
                "Run complete command text or script source through the frozen execution profile. "
                        + "Use COMMAND without language or args to invoke the configured host shell. "
                        + "Use SCRIPT with an explicit configured language and optional args. "
                        + "Every normal process exit is returned as EXITED with the raw exit code; the caller interprets its business meaning. "
                        + "The host operating system and runtime executables are trusted configuration.",
                new ToolSchema(
                        "haifa.execution.run.input",
                        "3.0.0",
                        inputSchema(
                                configurationIdentity,
                                scratchSpecDigest,
                                maximumTimeout,
                                workingDirectoryAllowed,
                                scriptLanguages)),
                new ToolSchema("haifa.execution.run.output", "3.0.0", outputSchema()),
                ToolExecutionMode.HOST_PROCESS,
                true,
                maximumTimeout,
                "per-workspace-execution",
                ToolIdempotency.NON_IDEMPOTENT,
                ToolRisk.HIGH,
                effects,
                new ToolResourceRequirements(
                        Set.of("execution_run"),
                        networkAllowed ? Set.of("unrestricted-network") : Set.of(),
                        Set.of(executionProfileIdentity)),
                List.of(),
                ToolApprovalRequirement.ALWAYS,
                "haifa-execution",
                false,
                Set.of("execution", "command", "script"));
    }

    private static Map<String, Object> inputSchema(
            String configurationIdentity,
            String scratchSpecDigest,
            Duration maximumTimeout,
            boolean workingDirectoryAllowed,
            Set<String> scriptLanguages) {
        if (scratchSpecDigest == null || !scratchSpecDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("scratchSpecDigest must be a lowercase SHA-256 digest");
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "mode",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("COMMAND", "SCRIPT"),
                        "description",
                        "COMMAND invokes the configured host shell and must omit language and args. SCRIPT requires "
                                + "an explicitly configured language."));
        commonProperties(properties, maximumTimeout);
        properties.put(
                "language",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        scriptLanguages.stream().sorted().toList(),
                        "description",
                        "Required for SCRIPT. Select one configured runtime; never send this field for COMMAND."));
        properties.put(
                "args",
                Map.of(
                        "type",
                        "array",
                        "maxItems",
                        16,
                        "items",
                        Map.of("type", "string", "maxLength", 1024),
                        "description",
                        "Optional arguments for SCRIPT only; never send this field for COMMAND."));
        if (workingDirectoryAllowed) {
            properties.put("workdir", workdirProperty());
        }
        return Map.ofEntries(
                Map.entry("$schema", ToolSchema.DRAFT_2020_12),
                Map.entry("x-haifa-configuration-identity", configurationIdentity),
                Map.entry("x-haifa-scratch-spec-digest", scratchSpecDigest),
                Map.entry("title", "Execution request"),
                Map.entry(
                        "description",
                        "Choose exactly one invocation mode. COMMAND uses the configured host shell: PowerShell on "
                                + "Windows and Bash or a POSIX shell on macOS/Linux; omit language and args. SCRIPT "
                                + "requires an explicitly configured language. Mode combinations are validated before "
                                + "policy and Approval."),
                Map.entry("type", "object"),
                Map.entry("properties", Map.copyOf(properties)),
                Map.entry("required", List.of("mode", "content", "purpose")),
                Map.entry("additionalProperties", false));
    }

    private static void commonProperties(Map<String, Object> properties, Duration maximumTimeout) {
        properties.put(
                "content",
                Map.of(
                        "type",
                        "string",
                        "minLength",
                        1,
                        "maxLength",
                        16_384,
                        "description",
                        "Complete command text or script source to execute after exact approval."));
        properties.put(
                "purpose",
                Map.of(
                        "type",
                        "string",
                        "minLength",
                        1,
                        "maxLength",
                        256,
                        "description",
                        "Short user-visible reason for this exact execution."));
        properties.put(
                "timeoutMillis",
                Map.of(
                        "type",
                        "integer",
                        "minimum",
                        1000,
                        "maximum",
                        maximumTimeout.toMillis(),
                        "description",
                        "Optional execution timeout in milliseconds."));
    }

    private static Map<String, Object> workdirProperty() {
        return Map.of(
                "type",
                "string",
                "minLength",
                1,
                "maxLength",
                4096,
                "description",
                "Optional product-authorized workspace-relative working directory.");
    }

    /**
     * The one canonical result contract of an execution Tool. Trusted script Tools reuse this exact
     * frozen schema instead of declaring their own, so a model-visible output schema always accepts
     * the envelope {@link ExecutionToolProvider} actually returns.
     */
    public static ToolSchema executionResultSchema() {
        return new ToolSchema("haifa.execution.run.output", "3.0.0", outputSchema());
    }

    private static Map<String, Object> outputSchema() {
        return Map.of(
                "$schema",
                ToolSchema.DRAFT_2020_12,
                "type",
                "object",
                "properties",
                Map.ofEntries(
                        Map.entry("processState", Map.of("type", "string")),
                        Map.entry("mode", Map.of("type", "string")),
                        Map.entry("language", Map.of("type", "string")),
                        Map.entry("exitCode", Map.of("type", "integer")),
                        Map.entry("timedOut", Map.of("type", "boolean")),
                        Map.entry("cancelled", Map.of("type", "boolean")),
                        Map.entry("stdoutSummary", Map.of("type", "string")),
                        Map.entry("stderrSummary", Map.of("type", "string")),
                        Map.entry("truncated", Map.of("type", "boolean")),
                        Map.entry("durationMillis", Map.of("type", "integer", "minimum", 0)),
                        Map.entry("scratchSpecDigest", Map.of("type", "string")),
                        Map.entry("scratchProvisioned", Map.of("type", "boolean")),
                        Map.entry("scratchCleanupFailed", Map.of("type", "boolean"))),
                "required",
                List.of(
                        "processState",
                        "mode",
                        "timedOut",
                        "cancelled",
                        "stdoutSummary",
                        "stderrSummary",
                        "truncated",
                        "durationMillis",
                        "scratchSpecDigest",
                        "scratchProvisioned",
                        "scratchCleanupFailed"),
                "additionalProperties",
                false);
    }
}
