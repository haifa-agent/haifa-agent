package io.haifa.agent.execution.core.tool;

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
        Set<ToolSideEffect> effects = networkAllowed
                ? Set.of(ToolSideEffect.PROCESS_EXECUTION, ToolSideEffect.NETWORK_ACCESS)
                : Set.of(ToolSideEffect.PROCESS_EXECUTION);
        return new ToolDefinition(
                new ToolName("execution.run"),
                new SemanticVersion("2.0.0"),
                ExecutionToolProvider.PROVIDER_ID,
                "Run an approved command or script",
                "Run complete command text or script source through the frozen execution profile. "
                        + "The host operating system and runtime executables are trusted configuration.",
                new ToolSchema(
                        "haifa.execution.run.input",
                        "2.0.0",
                        inputSchema(configurationIdentity, workingDirectoryAllowed, scriptLanguages)),
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
            String configurationIdentity, boolean workingDirectoryAllowed, Set<String> scriptLanguages) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("mode", Map.of("type", "string", "enum", List.of("COMMAND", "SCRIPT")));
        properties.put("content", Map.of("type", "string", "minLength", 1, "maxLength", 16_384));
        properties.put(
                "language",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        scriptLanguages.stream().sorted().toList()));
        properties.put(
                "args", Map.of("type", "array", "maxItems", 16, "items", Map.of("type", "string", "maxLength", 1024)));
        properties.put("purpose", Map.of("type", "string", "minLength", 1, "maxLength", 256));
        properties.put("timeoutMillis", Map.of("type", "integer", "minimum", 1000, "maximum", 30_000));
        if (workingDirectoryAllowed) {
            properties.put("workdir", Map.of("type", "string", "minLength", 1, "maxLength", 4096));
        }
        return Map.of(
                "$schema",
                ToolSchema.DRAFT_2020_12,
                "x-haifa-configuration-identity",
                configurationIdentity,
                "type",
                "object",
                "properties",
                Map.copyOf(properties),
                "required",
                List.of("mode", "content", "purpose"),
                "additionalProperties",
                false);
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
                        Map.entry("durationMillis", Map.of("type", "integer", "minimum", 0))),
                "required",
                List.of(
                        "status",
                        "mode",
                        "timedOut",
                        "cancelled",
                        "stdoutSummary",
                        "stderrSummary",
                        "truncated",
                        "durationMillis"),
                "additionalProperties",
                false);
    }
}
