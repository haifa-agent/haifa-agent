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
import java.util.Set;

/** Canonical v2 model-visible definition for command and script execution. */
public final class ExecutionToolDefinitionFactory {
    public static final ToolAlias ALIAS = new ToolAlias("execution_run");

    private ExecutionToolDefinitionFactory() {}

    public static ToolDefinition create(
            String executionProfileIdentity,
            boolean networkAllowed,
            boolean workingDirectoryAllowed,
            Set<String> scriptLanguages) {
        return create(
                executionProfileIdentity,
                executionProfileIdentity,
                ExecutionScratchSpaceSpec.genericRequired().canonicalDigest(),
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
                ExecutionScratchSpaceSpec.genericRequired().canonicalDigest(),
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
        Set<ToolSideEffect> effects = networkAllowed
                ? Set.of(ToolSideEffect.PROCESS_EXECUTION, ToolSideEffect.NETWORK_ACCESS)
                : Set.of(ToolSideEffect.PROCESS_EXECUTION);
        return new ToolDefinition(
                new ToolName("execution.run"),
                new SemanticVersion("2.0.0"),
                ExecutionToolProvider.PROVIDER_ID,
                "Run an approved command or script",
                "Run complete command text or script source through the frozen execution profile. "
                        + "Use COMMAND without language or args to invoke the configured host shell. "
                        + "Use SCRIPT with an explicit configured language and optional args. "
                        + "The host operating system and runtime executables are trusted configuration.",
                new ToolSchema(
                        "haifa.execution.run.input",
                        "2.0.0",
                        inputSchema(
                                configurationIdentity, scratchSpecDigest, workingDirectoryAllowed, scriptLanguages)),
                new ToolSchema("haifa.execution.run.output", "2.0.0", outputSchema()),
                ToolExecutionMode.HOST_PROCESS,
                true,
                Duration.ofSeconds(30),
                "per-workspace-execution",
                ToolIdempotency.NON_IDEMPOTENT,
                ToolRisk.HIGH,
                effects,
                new ToolResourceRequirements(
                        Set.of("execution.run"),
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
        commonProperties(properties);
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

    private static void commonProperties(Map<String, Object> properties) {
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
                        30_000,
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

    private static Map<String, Object> outputSchema() {
        return Map.of(
                "$schema",
                ToolSchema.DRAFT_2020_12,
                "type",
                "object",
                "properties",
                Map.ofEntries(
                        Map.entry("status", Map.of("type", "string")),
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
                        "status",
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
