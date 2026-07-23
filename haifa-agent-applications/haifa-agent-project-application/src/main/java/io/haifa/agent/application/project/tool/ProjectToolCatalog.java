package io.haifa.agent.application.project.tool;

import io.haifa.agent.mcp.tool.McpToolCatalogContribution;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import io.haifa.agent.tool.core.DefaultToolCatalog;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds the project product's model-visible tools through the platform Tool catalog. */
public final class ProjectToolCatalog {
    private static final Map<String, String> REQUIRED_CAPABILITY = Map.ofEntries(
            Map.entry("file.list", "file.read"), Map.entry("file.stat", "file.read"),
            Map.entry("file.read", "file.read"), Map.entry("file.search", "file.read"),
            Map.entry("file.create", "file.write"), Map.entry("file.write", "file.write"),
            Map.entry("file.delete", "file.write"), Map.entry("file.move", "file.write"),
            Map.entry("file.diff", "file.read"), Map.entry("file.patch", "file.write"),
            Map.entry("git.inspect", "git.read"), Map.entry("git.status", "git.read"),
            Map.entry("git.diff", "git.read"), Map.entry("execution.run", "execution.run"));
    private static final Set<String> WRITES =
            Set.of("file.create", "file.write", "file.delete", "file.move", "file.patch");

    public DefaultToolCatalog freeze(
            Set<String> configuredTools,
            Set<String> effectiveCapabilities,
            boolean modelSupportsTools,
            ToolProvider provider) {
        Objects.requireNonNull(configuredTools, "configuredTools");
        Objects.requireNonNull(effectiveCapabilities, "effectiveCapabilities");
        Objects.requireNonNull(provider, "provider");
        ToolCatalogBuilder builder = new ToolCatalogBuilder();
        if (!modelSupportsTools) return builder.freeze();
        REQUIRED_CAPABILITY.keySet().stream()
                .sorted()
                .filter(configuredTools::contains)
                .filter(name -> effectiveCapabilities.contains(REQUIRED_CAPABILITY.get(name)))
                .forEach(name -> builder.register(modelAlias(name), definition(name), "project-workspace", provider));
        return builder.freeze();
    }

    /** Coding profile assembly path for locally reviewed MCP imports and built-in project tools. */
    public DefaultToolCatalog freeze(
            Set<String> configuredTools,
            Set<String> effectiveCapabilities,
            boolean modelSupportsTools,
            ToolProvider provider,
            List<McpToolCatalogContribution> mcpTools) {
        Objects.requireNonNull(mcpTools, "mcpTools");
        Objects.requireNonNull(configuredTools, "configuredTools");
        Objects.requireNonNull(effectiveCapabilities, "effectiveCapabilities");
        Objects.requireNonNull(provider, "provider");
        ToolCatalogBuilder builder = new ToolCatalogBuilder();
        if (!modelSupportsTools) return builder.freeze();
        REQUIRED_CAPABILITY.keySet().stream()
                .sorted()
                .filter(configuredTools::contains)
                .filter(name -> effectiveCapabilities.contains(REQUIRED_CAPABILITY.get(name)))
                .forEach(name -> builder.register(modelAlias(name), definition(name), "project-workspace", provider));
        mcpTools.stream()
                .sorted(java.util.Comparator.comparing(McpToolCatalogContribution::alias))
                .forEach(contribution -> builder.register(
                        contribution.alias(),
                        contribution.definition(),
                        contribution.providerBindingReference(),
                        contribution.provider()));
        return builder.freeze();
    }

    public Set<String> names() {
        return REQUIRED_CAPABILITY.keySet();
    }

    private static ToolAlias modelAlias(String name) {
        return new ToolAlias(name.replace('.', '_'));
    }

    private static ToolDefinition definition(String name) {
        boolean execution = name.equals("execution.run");
        boolean write = WRITES.contains(name);
        ToolRisk risk = execution ? ToolRisk.HIGH : write ? ToolRisk.MEDIUM : ToolRisk.LOW;
        ToolIdempotency idempotency = execution || write ? ToolIdempotency.NON_IDEMPOTENT : ToolIdempotency.PURE;
        Set<ToolSideEffect> effects = execution
                ? Set.of(ToolSideEffect.PROCESS_EXECUTION)
                : write ? Set.of(ToolSideEffect.FILE_WRITE) : Set.of(ToolSideEffect.FILE_READ);
        ToolApprovalRequirement approval =
                execution || write ? ToolApprovalRequirement.POLICY : ToolApprovalRequirement.NEVER;
        ToolResourceRequirements resources = new ToolResourceRequirements(
                Set.of(REQUIRED_CAPABILITY.get(name)), Set.of(), execution ? Set.of("project-safe") : Set.of());
        return new ToolDefinition(
                new ToolName(name),
                new SemanticVersion("1.0.0"),
                ProjectToolExecutor.PROVIDER_ID,
                title(name),
                description(name),
                new ToolSchema("haifa." + name + ".input", "1.0.0", inputSchema(name)),
                new ToolSchema("haifa." + name + ".output", "1.0.0", outputSchema(name)),
                execution ? ToolExecutionMode.HOST_PROCESS : ToolExecutionMode.IN_PROCESS,
                true,
                execution ? Duration.ofMinutes(30) : Duration.ofSeconds(30),
                write ? "per-workspace-write" : "per-workspace-read",
                idempotency,
                risk,
                effects,
                resources,
                List.of(),
                approval,
                "haifa-project-application",
                false,
                Set.of("project", name.substring(0, name.indexOf('.'))));
    }

    private static String title(String name) {
        return switch (name) {
            case "file.list" -> "List workspace files";
            case "file.stat" -> "Inspect workspace path";
            case "file.read" -> "Read workspace file";
            case "file.search" -> "Search workspace files";
            case "file.create" -> "Create workspace file";
            case "file.write" -> "Write workspace file";
            case "file.delete" -> "Delete workspace path";
            case "file.move" -> "Move workspace path";
            case "file.diff" -> "Preview file diff";
            case "file.patch" -> "Apply workspace patch";
            case "git.inspect" -> "Inspect Git repository";
            case "git.status" -> "Read Git status";
            case "git.diff" -> "Read Git diff";
            case "execution.run" -> "Run a local shell command";
            default -> throw new IllegalArgumentException("unknown project tool " + name);
        };
    }

    private static String description(String name) {
        if (name.equals("execution.run")) {
            return "Run complete command text through the configured host shell inside the frozen project workspace.";
        }
        return title(name) + " within the frozen project workspace and capability boundary.";
    }

    private static Map<String, Object> inputSchema(String name) {
        var properties = new LinkedHashMap<String, Object>();
        var required = new java.util.ArrayList<String>();
        switch (name) {
            case "file.list" -> {
                path(properties, required, "path");
                properties.put("recursive", Map.of("type", "boolean"));
                properties.put("maxDepth", Map.of("type", "integer", "minimum", 1, "maximum", 32));
            }
            case "file.stat", "file.read", "file.delete" -> path(properties, required, "path");
            case "file.search" -> {
                path(properties, required, "path");
                string(properties, required, "query");
                properties.put("glob", Map.of("type", "string"));
                properties.put("maxResults", Map.of("type", "integer", "minimum", 1, "maximum", 1000));
            }
            case "file.create", "file.write", "file.diff" -> {
                path(properties, required, "path");
                string(properties, required, "content");
            }
            case "file.move" -> {
                path(properties, required, "source");
                path(properties, required, "destination");
            }
            case "file.patch" -> {
                path(properties, required, "path");
                string(properties, required, "patch");
            }
            case "git.inspect", "git.status" -> properties.put("includeIgnored", Map.of("type", "boolean"));
            case "git.diff" -> {
                properties.put("staged", Map.of("type", "boolean"));
                properties.put("paths", Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 100));
            }
            case "execution.run" -> {
                properties.put("command", Map.of("type", "string", "minLength", 1, "maxLength", 32768));
                required.add("command");
                properties.put("workdir", Map.of("type", "string", "minLength", 1, "maxLength", 4096));
                properties.put("timeoutMillis", Map.of("type", "integer", "minimum", 1, "maximum", 1800000));
                properties.put("description", Map.of("type", "string", "minLength", 1, "maxLength", 256));
            }
            default -> throw new IllegalArgumentException("unknown project tool " + name);
        }
        return Map.of(
                "$schema",
                ToolSchema.DRAFT_2020_12,
                "type",
                "object",
                "properties",
                Map.copyOf(properties),
                "required",
                List.copyOf(required),
                "additionalProperties",
                false);
    }

    private static Map<String, Object> outputSchema(String name) {
        if (name.equals("execution.run")) {
            return Map.of(
                    "$schema",
                    ToolSchema.DRAFT_2020_12,
                    "type",
                    "object",
                    "properties",
                    Map.ofEntries(
                            Map.entry("executionId", Map.of("type", "string")),
                            Map.entry("status", Map.of("type", "string")),
                            Map.entry("exitCode", Map.of("type", "integer")),
                            Map.entry("output", Map.of("type", "string")),
                            Map.entry("truncated", Map.of("type", "boolean")),
                            Map.entry("outputRef", Map.of("type", "string")),
                            Map.entry("outputRefs", Map.of("type", "array", "items", Map.of("type", "string"))),
                            Map.entry("fileChangeSetId", Map.of("type", "string")),
                            Map.entry("durationMillis", Map.of("type", "integer", "minimum", 0)),
                            Map.entry("failureCode", Map.of("type", "string")),
                            Map.entry("failureDetail", Map.of("type", "string"))),
                    "required",
                    List.of("executionId", "status", "output", "truncated", "durationMillis"),
                    "additionalProperties",
                    false);
        }
        return Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", true);
    }

    private static void path(Map<String, Object> properties, List<String> required, String name) {
        properties.put(
                name,
                Map.of(
                        "type",
                        "string",
                        "minLength",
                        1,
                        "description",
                        "Workspace-relative path; use '.' for the workspace root. Absolute paths are not allowed."));
        required.add(name);
    }

    private static void string(Map<String, Object> properties, List<String> required, String name) {
        properties.put(name, Map.of("type", "string"));
        required.add(name);
    }
}
