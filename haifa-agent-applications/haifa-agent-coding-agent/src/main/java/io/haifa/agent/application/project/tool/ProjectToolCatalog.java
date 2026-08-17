package io.haifa.agent.application.project.tool;

import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
import io.haifa.agent.mcp.tool.McpToolCatalogContribution;
import io.haifa.agent.runtime.core.skill.SkillToolCatalogContribution;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxProfile;
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
import io.haifa.agent.web.WebToolCatalogContribution;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds the project product's model-visible tools through the platform Tool catalog. */
public final class ProjectToolCatalog {
    private static final Map<String, String> REQUIRED_CAPABILITY = Map.ofEntries(
            Map.entry("file.list", "file.read"),
            Map.entry("file.stat", "file.read"),
            Map.entry("file.read", "file.read"),
            Map.entry("file.search", "file.read"),
            Map.entry("file.create", "file.write"),
            Map.entry("file.write", "file.write"),
            Map.entry("file.delete", "file.write"),
            Map.entry("file.move", "file.write"),
            Map.entry("file.diff", "file.read"),
            Map.entry("file.patch", "file.write"),
            Map.entry("execution.run", "execution.run"),
            Map.entry(ProjectPermissionRequestOperations.TOOL_NAME, "execution.run"));
    private static final Set<String> WRITES =
            Set.of("file.create", "file.write", "file.delete", "file.move", "file.patch");

    public DefaultToolCatalog freeze(
            Set<String> configuredTools,
            Set<String> effectiveCapabilities,
            boolean modelSupportsTools,
            ToolProvider provider) {
        return freeze(
                configuredTools,
                effectiveCapabilities,
                modelSupportsTools,
                provider,
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    public DefaultToolCatalog freeze(
            Set<String> configuredTools,
            Set<String> effectiveCapabilities,
            boolean modelSupportsTools,
            ToolProvider provider,
            SandboxProfile executionProfile) {
        return freeze(
                configuredTools,
                effectiveCapabilities,
                modelSupportsTools,
                provider,
                List.of(),
                List.of(),
                List.of(),
                Objects.requireNonNull(executionProfile, "executionProfile"),
                ExecutionScratchSpaceSpec.genericRequired());
    }

    /** Coding profile assembly path for locally reviewed MCP imports and built-in project tools. */
    public DefaultToolCatalog freeze(
            Set<String> configuredTools,
            Set<String> effectiveCapabilities,
            boolean modelSupportsTools,
            ToolProvider provider,
            List<McpToolCatalogContribution> mcpTools) {
        return freeze(
                configuredTools,
                effectiveCapabilities,
                modelSupportsTools,
                provider,
                mcpTools,
                List.of(),
                List.of(),
                null);
    }

    /** Coding profile assembly path for reviewed MCP imports, Web capabilities, and built-in project tools. */
    public DefaultToolCatalog freeze(
            Set<String> configuredTools,
            Set<String> effectiveCapabilities,
            boolean modelSupportsTools,
            ToolProvider provider,
            List<McpToolCatalogContribution> mcpTools,
            List<WebToolCatalogContribution> webTools) {
        return freeze(
                configuredTools,
                effectiveCapabilities,
                modelSupportsTools,
                provider,
                mcpTools,
                webTools,
                List.of(),
                null);
    }

    /** Full project product assembly path; Skill tools are supplied only by Skill-enabled profiles. */
    public DefaultToolCatalog freeze(
            Set<String> configuredTools,
            Set<String> effectiveCapabilities,
            boolean modelSupportsTools,
            ToolProvider provider,
            List<McpToolCatalogContribution> mcpTools,
            List<WebToolCatalogContribution> webTools,
            List<SkillToolCatalogContribution> skillTools) {
        return freeze(
                configuredTools,
                effectiveCapabilities,
                modelSupportsTools,
                provider,
                mcpTools,
                webTools,
                skillTools,
                null);
    }

    public DefaultToolCatalog freeze(
            Set<String> configuredTools,
            Set<String> effectiveCapabilities,
            boolean modelSupportsTools,
            ToolProvider provider,
            List<McpToolCatalogContribution> mcpTools,
            List<WebToolCatalogContribution> webTools,
            List<SkillToolCatalogContribution> skillTools,
            SandboxProfile executionProfile) {
        return freeze(
                configuredTools,
                effectiveCapabilities,
                modelSupportsTools,
                provider,
                mcpTools,
                webTools,
                skillTools,
                executionProfile,
                ExecutionScratchSpaceSpec.genericRequired());
    }

    public DefaultToolCatalog freeze(
            Set<String> configuredTools,
            Set<String> effectiveCapabilities,
            boolean modelSupportsTools,
            ToolProvider provider,
            List<McpToolCatalogContribution> mcpTools,
            List<WebToolCatalogContribution> webTools,
            List<SkillToolCatalogContribution> skillTools,
            SandboxProfile executionProfile,
            ExecutionScratchSpaceSpec scratchSpace) {
        return freeze(
                configuredTools,
                effectiveCapabilities,
                modelSupportsTools,
                provider,
                mcpTools,
                webTools,
                skillTools,
                executionProfile,
                executionProfile,
                scratchSpace);
    }

    public DefaultToolCatalog freeze(
            Set<String> configuredTools,
            Set<String> effectiveCapabilities,
            boolean modelSupportsTools,
            ToolProvider provider,
            List<McpToolCatalogContribution> mcpTools,
            List<WebToolCatalogContribution> webTools,
            List<SkillToolCatalogContribution> skillTools,
            SandboxProfile executionProfile,
            SandboxProfile permissionProfile,
            ExecutionScratchSpaceSpec scratchSpace) {
        Objects.requireNonNull(mcpTools, "mcpTools");
        Objects.requireNonNull(webTools, "webTools");
        Objects.requireNonNull(skillTools, "skillTools");
        Objects.requireNonNull(configuredTools, "configuredTools");
        Objects.requireNonNull(effectiveCapabilities, "effectiveCapabilities");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(scratchSpace, "scratchSpace");
        ToolCatalogBuilder builder = new ToolCatalogBuilder();
        if (!modelSupportsTools) return builder.freeze();
        REQUIRED_CAPABILITY.keySet().stream()
                .sorted()
                .filter(configuredTools::contains)
                .filter(name -> effectiveCapabilities.contains(REQUIRED_CAPABILITY.get(name)))
                .forEach(name -> builder.register(
                        modelAlias(name),
                        definition(
                                name,
                                name.equals(ProjectPermissionRequestOperations.TOOL_NAME)
                                        ? permissionProfile
                                        : executionProfile,
                                scratchSpace),
                        "project-workspace",
                        provider));
        mcpTools.stream()
                .sorted(java.util.Comparator.comparing(McpToolCatalogContribution::alias))
                .forEach(contribution -> builder.register(
                        contribution.alias(),
                        contribution.definition(),
                        contribution.providerBindingReference(),
                        contribution.provider()));
        webTools.stream()
                .sorted(java.util.Comparator.comparing(WebToolCatalogContribution::alias))
                .forEach(contribution -> builder.register(
                        contribution.alias(),
                        contribution.definition(),
                        contribution.providerBindingReference(),
                        contribution.provider()));
        skillTools.stream()
                .sorted(java.util.Comparator.comparing(SkillToolCatalogContribution::alias))
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
        if (name.equals(ProjectPermissionRequestOperations.TOOL_NAME)) {
            return new ToolAlias(ProjectPermissionRequestOperations.MODEL_ALIAS);
        }
        return new ToolAlias(name.replace('.', '_'));
    }

    private static ToolDefinition definition(
            String name, SandboxProfile executionProfile, ExecutionScratchSpaceSpec scratchSpace) {
        boolean permissionRequest = name.equals(ProjectPermissionRequestOperations.TOOL_NAME);
        boolean execution = name.equals("execution.run") || permissionRequest;
        if (execution && executionProfile == null) {
            throw new IllegalArgumentException(name + " requires a frozen sandbox profile");
        }
        boolean write = WRITES.contains(name);
        ToolRisk risk = permissionRequest
                ? ToolRisk.CRITICAL
                : execution ? ToolRisk.HIGH : write ? ToolRisk.MEDIUM : ToolRisk.LOW;
        ToolIdempotency idempotency = execution || write ? ToolIdempotency.NON_IDEMPOTENT : ToolIdempotency.PURE;
        Set<ToolSideEffect> effects = execution
                ? executionProfile.networkPolicy() == NetworkPolicy.ALLOW
                        ? Set.of(ToolSideEffect.PROCESS_EXECUTION, ToolSideEffect.NETWORK_ACCESS)
                        : Set.of(ToolSideEffect.PROCESS_EXECUTION)
                : write ? Set.of(ToolSideEffect.FILE_WRITE) : Set.of(ToolSideEffect.FILE_READ);
        ToolApprovalRequirement approval = permissionRequest
                ? ToolApprovalRequirement.ALWAYS
                : execution || write ? ToolApprovalRequirement.POLICY : ToolApprovalRequirement.NEVER;
        ToolResourceRequirements resources = new ToolResourceRequirements(
                Set.of(REQUIRED_CAPABILITY.get(name)),
                execution && executionProfile.networkPolicy() == NetworkPolicy.ALLOW
                        ? Set.of("unrestricted-network")
                        : Set.of(),
                execution ? Set.of(executionProfileIdentity(executionProfile)) : Set.of());
        String version = name.equals("file.read") || name.equals("file.patch") ? "1.1.0" : "1.0.0";
        return new ToolDefinition(
                new ToolName(name),
                new SemanticVersion(version),
                ProjectToolExecutor.PROVIDER_ID,
                title(name),
                description(name, executionProfile),
                new ToolSchema(
                        "haifa." + name + ".input",
                        version,
                        inputSchema(name, execution ? scratchSpace.canonicalDigest() : null)),
                new ToolSchema("haifa." + name + ".output", version, outputSchema(name)),
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
                "haifa-coding-agent",
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
            case "execution.run" -> "Run a local shell command";
            case ProjectPermissionRequestOperations.TOOL_NAME -> "Request permission for one failed command";
            default -> throw new IllegalArgumentException("unknown project tool " + name);
        };
    }

    private static String description(String name, SandboxProfile executionProfile) {
        if (name.equals("execution.run")) {
            return "Run complete command text through the frozen "
                    + executionProfile.providerId()
                    + " execution profile inside the project workspace. This is the general OS CLI path for scalable "
                    + "repository discovery, content search, source inspection, system git/gh workflows, builds, "
                    + "tests, and diffs; choose an "
                    + "available CLI and its complete arguments at runtime instead of expecting command-specific "
                    + "wrappers. Keep output bounded, adapt when a command is unavailable, and classify every call "
                    + "with operationFamily, using BUILD or TEST for validation and DIFF only for read-only final "
                    + "diff inspection.";
        }
        if (name.equals(ProjectPermissionRequestOperations.TOOL_NAME)) {
            return "Request user approval to rerun one exact execution.run command that failed in this Run because "
                    + "the isolated profile could not use remote network or host authentication. Only direct, "
                    + "non-destructive system git or gh commands are eligible. The prior Tool Call, command, workdir, "
                    + "operation family, and timeout must match; "
                    + "compound, wrapped, path-escaping, credential-overriding, destructive, or outcome-unknown "
                    + "requests remain denied. Approval applies once to this Tool Call and does not create a reusable "
                    + "grant.";
        }
        if (name.equals("file.read")) {
            return "Read one bounded text window from a workspace file. Continue with nextCursor only when hasMore "
                    + "is true; the cursor detects path reuse and file changes, so large files are never loaded in "
                    + "full by default.";
        }
        if (name.equals("file.patch")) {
            return "Apply a bounded context patch to one or more workspace files. Use *** Begin Patch / "
                    + "*** End Patch with Add File, Delete File, or Update File sections; Update File supports "
                    + "optional Move to and @@ context hunks. Existing files are matched exactly, large files are "
                    + "transformed as streams, and failures report the exact committed prefix.";
        }
        if (WRITES.contains(name)) {
            return title(name)
                    + " within the frozen project workspace and capability boundary. Preserve unrelated user changes.";
        }
        return title(name) + " within the frozen project workspace and capability boundary.";
    }

    static String executionProfileIdentity(SandboxProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        return profile.ref().value() + "@" + profile.ref().version();
    }

    private static Map<String, Object> inputSchema(String name, String scratchSpecDigest) {
        var properties = new LinkedHashMap<String, Object>();
        var required = new java.util.ArrayList<String>();
        switch (name) {
            case "file.list" -> {
                path(properties, required, "path");
                properties.put("recursive", Map.of("type", "boolean"));
                properties.put("maxDepth", Map.of("type", "integer", "minimum", 1, "maximum", 32));
            }
            case "file.stat", "file.delete" -> path(properties, required, "path");
            case "file.read" -> {
                path(properties, required, "path");
                properties.put(
                        "cursor",
                        Map.of(
                                "type",
                                "string",
                                "minLength",
                                1,
                                "maxLength",
                                2048,
                                "description",
                                "Opaque nextCursor returned by the preceding read of the same unchanged file."));
                properties.put("maxBytes", Map.of("type", "integer", "minimum", 1, "maximum", 262144));
                properties.put("maxLines", Map.of("type", "integer", "minimum", 1, "maximum", 2000));
            }
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
                properties.put(
                        "patch",
                        Map.of(
                                "type",
                                "string",
                                "minLength",
                                1,
                                "maxLength",
                                4194304,
                                "description",
                                "Context patch beginning with *** Begin Patch and ending with *** End Patch."));
                required.add("patch");
            }
            case "execution.run", ProjectPermissionRequestOperations.TOOL_NAME -> {
                properties.put(
                        "command",
                        Map.of(
                                "type",
                                "string",
                                "minLength",
                                1,
                                "maxLength",
                                32768,
                                "description",
                                "Complete non-interactive command text for the configured shell. Select available "
                                        + "CLI programs and their options at runtime."));
                required.add("command");
                properties.put("workdir", Map.of("type", "string", "minLength", 1, "maxLength", 4096));
                properties.put("timeoutMillis", Map.of("type", "integer", "minimum", 1, "maximum", 1800000));
                properties.put("description", Map.of("type", "string", "minLength", 1, "maxLength", 256));
                properties.put(
                        "operationFamily",
                        Map.of(
                                "type",
                                "string",
                                "enum",
                                List.of("BUILD", "TEST", "DIFF", "INSPECT", "MUTATE", "UNKNOWN"),
                                "description",
                                "Stable operation family for delivery and recovery control. Use DIFF only for "
                                        + "read-only diff inspection and UNKNOWN when the command cannot "
                                        + "be reliably classified; do not infer it from arbitrary shell syntax."));
                required.add("operationFamily");
                if (name.equals(ProjectPermissionRequestOperations.TOOL_NAME)) {
                    properties.put("priorToolCallId", Map.of("type", "string", "minLength", 1, "maxLength", 256));
                    properties.put(
                            "requestedPermission",
                            Map.of(
                                    "type",
                                    "string",
                                    "enum",
                                    List.of(ProjectPermissionRequestOperations.HOST_NETWORK_ACCESS)));
                    properties.put("justification", Map.of("type", "string", "minLength", 1, "maxLength", 512));
                    required.add("priorToolCallId");
                    required.add("requestedPermission");
                    required.add("justification");
                }
            }
            default -> throw new IllegalArgumentException("unknown project tool " + name);
        }
        var schema = new LinkedHashMap<String, Object>();
        schema.put("$schema", ToolSchema.DRAFT_2020_12);
        if (scratchSpecDigest != null) {
            schema.put("x-haifa-scratch-spec-digest", scratchSpecDigest);
        }
        schema.put("type", "object");
        schema.put("properties", Map.copyOf(properties));
        schema.put("required", List.copyOf(required));
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private static Map<String, Object> outputSchema(String name) {
        if (name.equals("file.read")) {
            return Map.of(
                    "$schema",
                    ToolSchema.DRAFT_2020_12,
                    "type",
                    "object",
                    "properties",
                    Map.ofEntries(
                            Map.entry("path", Map.of("type", "string")),
                            Map.entry("content", Map.of("type", "string")),
                            Map.entry("startLine", Map.of("type", "integer", "minimum", 1)),
                            Map.entry("endLine", Map.of("type", "integer", "minimum", 1)),
                            Map.entry("bytesRead", Map.of("type", "integer", "minimum", 0)),
                            Map.entry("totalBytes", Map.of("type", "integer", "minimum", 0)),
                            Map.entry("contentVersion", Map.of("type", "string")),
                            Map.entry("hasMore", Map.of("type", "boolean")),
                            Map.entry("nextCursor", Map.of("type", "string")),
                            Map.entry("truncated", Map.of("type", "boolean"))),
                    "required",
                    List.of(
                            "path",
                            "content",
                            "startLine",
                            "endLine",
                            "bytesRead",
                            "totalBytes",
                            "contentVersion",
                            "hasMore",
                            "truncated"),
                    "additionalProperties",
                    false);
        }
        if (name.equals("execution.run") || name.equals(ProjectPermissionRequestOperations.TOOL_NAME)) {
            var properties = new LinkedHashMap<String, Object>();
            properties.put("toolCallId", Map.of("type", "string"));
            properties.put("executionId", Map.of("type", "string"));
            properties.put("status", Map.of("type", "string"));
            properties.put("exitCode", Map.of("type", "integer"));
            properties.put("output", Map.of("type", "string"));
            properties.put("truncated", Map.of("type", "boolean"));
            properties.put("outputRef", Map.of("type", "string"));
            properties.put("outputRefs", Map.of("type", "array", "items", Map.of("type", "string")));
            properties.put("fileChangeSetId", Map.of("type", "string"));
            properties.put("durationMillis", Map.of("type", "integer", "minimum", 0));
            properties.put("failureCode", Map.of("type", "string"));
            properties.put("failureDetail", Map.of("type", "string"));
            properties.put("failureCategory", Map.of("type", "string"));
            properties.put("stableFailureCode", Map.of("type", "string"));
            properties.put("resourceClass", Map.of("type", "string"));
            properties.put("operationFamily", Map.of("type", "string"));
            properties.put("commandTarget", Map.of("type", "string"));
            properties.put("commandRisk", Map.of("type", "string"));
            properties.put("commandClassificationReason", Map.of("type", "string"));
            properties.put("sandboxProfileDigest", Map.of("type", "string"));
            properties.put("scratchSpecDigest", Map.of("type", "string"));
            properties.put("scratchProvisioned", Map.of("type", "boolean"));
            properties.put("scratchCleanupFailed", Map.of("type", "boolean"));
            if (name.equals(ProjectPermissionRequestOperations.TOOL_NAME)) {
                properties.put("permissionEscalated", Map.of("type", "boolean"));
                properties.put("requestedPermission", Map.of("type", "string"));
                properties.put("priorToolCallId", Map.of("type", "string"));
            }
            return Map.of(
                    "$schema",
                    ToolSchema.DRAFT_2020_12,
                    "type",
                    "object",
                    "properties",
                    Map.copyOf(properties),
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
