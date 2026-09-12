package io.haifa.agent.application.project.tool;

import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
import io.haifa.agent.mcp.tool.McpToolCatalogContribution;
import io.haifa.agent.runtime.core.skill.SkillToolCatalogContribution;
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
            Map.entry("file_list", "file_read"),
            Map.entry("file_stat", "file_read"),
            Map.entry("file_read", "file_read"),
            Map.entry("file_search", "file_read"),
            Map.entry("file_create", "file_write"),
            Map.entry("file_write", "file_write"),
            Map.entry("file_delete", "file_write"),
            Map.entry("file_move", "file_write"),
            Map.entry("file_diff", "file_read"),
            Map.entry("file_patch", "file_write"),
            Map.entry("workspace_attach", "file_read"),
            Map.entry(ProjectWorktreeToolOperations.TOOL_NAME, "execution_run"),
            Map.entry("execution_run", "execution_run"));
    private static final Set<String> WRITES =
            Set.of("file_create", "file_write", "file_delete", "file_move", "file_patch");

    public DefaultToolCatalog freeze(
            Set<String> configuredTools,
            Set<String> effectiveCapabilities,
            boolean modelSupportsTools,
            ToolProvider provider) {
        return freezeInternal(
                configuredTools,
                effectiveCapabilities,
                modelSupportsTools,
                provider,
                List.of(),
                List.of(),
                List.of(),
                null,
                ExecutionScratchSpaceSpec.genericRequired());
    }

    public DefaultToolCatalog freeze(
            Set<String> configuredTools,
            Set<String> effectiveCapabilities,
            boolean modelSupportsTools,
            ToolProvider provider,
            SandboxProfile executionProfile) {
        return freezeInternal(
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
        return freezeInternal(
                configuredTools,
                effectiveCapabilities,
                modelSupportsTools,
                provider,
                mcpTools,
                webTools,
                skillTools,
                executionProfile,
                scratchSpace);
    }

    private DefaultToolCatalog freezeInternal(
            Set<String> configuredTools,
            Set<String> effectiveCapabilities,
            boolean modelSupportsTools,
            ToolProvider provider,
            List<McpToolCatalogContribution> mcpTools,
            List<WebToolCatalogContribution> webTools,
            List<SkillToolCatalogContribution> skillTools,
            SandboxProfile executionProfile,
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
                        new ToolAlias(name),
                        definition(name, executionProfile, scratchSpace),
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

    private static ToolDefinition definition(
            String name, SandboxProfile executionProfile, ExecutionScratchSpaceSpec scratchSpace) {
        boolean execution = name.equals("execution_run");
        if (execution && executionProfile == null) {
            throw new IllegalArgumentException(name + " requires a frozen sandbox profile");
        }
        boolean attach = name.equals("workspace_attach");
        boolean worktree = name.equals(ProjectWorktreeToolOperations.TOOL_NAME);
        boolean write = WRITES.contains(name);
        ToolRisk risk = execution || attach || worktree ? ToolRisk.HIGH : write ? ToolRisk.MEDIUM : ToolRisk.LOW;
        ToolIdempotency idempotency =
                execution || write || attach || worktree ? ToolIdempotency.NON_IDEMPOTENT : ToolIdempotency.PURE;
        Set<ToolSideEffect> effects = attach
                ? Set.of(ToolSideEffect.FILE_READ, ToolSideEffect.PERMISSION_ELEVATION)
                : worktree
                        ? Set.of(
                                ToolSideEffect.FILE_WRITE,
                                ToolSideEffect.PROCESS_EXECUTION,
                                ToolSideEffect.PERMISSION_ELEVATION)
                        : executionEffects(execution, write);
        ToolApprovalRequirement approval = attach || worktree
                ? ToolApprovalRequirement.ALWAYS
                : execution || write ? ToolApprovalRequirement.POLICY : ToolApprovalRequirement.NEVER;
        ToolResourceRequirements resources = new ToolResourceRequirements(
                Set.of(REQUIRED_CAPABILITY.get(name)),
                execution ? Set.of("unrestricted-network") : Set.of(),
                execution ? Set.of(executionProfileIdentity(executionProfile)) : Set.of());
        String version =
                switch (name) {
                    case "execution_run" -> "2.0.0";
                    case "file_patch" -> "2.1.0";
                    case "file_list",
                            "file_read",
                            "file_search",
                            "file_write",
                            "file_create",
                            "file_delete",
                            "file_move",
                            "file_diff",
                            "file_stat" -> "2.0.0";
                    case "workspace_attach" -> "3.0.0";
                    case ProjectWorktreeToolOperations.TOOL_NAME -> "2.0.0";
                    default -> "1.0.0";
                };
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
                write || attach || worktree ? "per-workspace-write" : "per-workspace-read",
                idempotency,
                risk,
                effects,
                resources,
                List.of(),
                approval,
                "haifa-coding-agent",
                false,
                Set.of("project", name.substring(0, name.indexOf('_'))));
    }

    private static Set<ToolSideEffect> executionEffects(boolean execution, boolean write) {
        if (!execution) return write ? Set.of(ToolSideEffect.FILE_WRITE) : Set.of(ToolSideEffect.FILE_READ);
        // Host execution always reaches the ordinary host network; the platform cannot deny it.
        return Set.of(ToolSideEffect.PROCESS_EXECUTION, ToolSideEffect.NETWORK_ACCESS);
    }

    private static String title(String name) {
        return switch (name) {
            case "file_list" -> "List workspace files";
            case "file_stat" -> "Inspect workspace path";
            case "file_read" -> "Read workspace file";
            case "file_search" -> "Search workspace files";
            case "file_create" -> "Create workspace file";
            case "file_write" -> "Write workspace file";
            case "file_delete" -> "Delete workspace path";
            case "file_move" -> "Move workspace path";
            case "file_diff" -> "Preview file diff";
            case "file_patch" -> "Apply workspace patch";
            case "workspace_attach" -> "Attach a user-approved directory";
            case ProjectWorktreeToolOperations.TOOL_NAME -> "Create a controlled Git worktree";
            case "execution_run" -> "Run a local shell command";
            default -> throw new IllegalArgumentException("unknown project tool " + name);
        };
    }

    private static String description(String name, SandboxProfile executionProfile) {
        if (name.equals("execution_run")) {
            return "Run complete command text through the frozen "
                    + executionProfile.providerId()
                    + " execution profile inside an active registered workspace selected by workspaceRef and relativeWorkdir. This is the general OS CLI path for scalable "
                    + "repository discovery, content search, source inspection, system git/gh workflows, builds, "
                    + "tests, and diffs; choose an "
                    + "available CLI and its complete arguments at runtime instead of expecting command-specific "
                    + "wrappers. Output is always bounded by operation family; use paging or returned artifact refs "
                    + "instead of repeating broad commands, and adapt when a command is unavailable. operationFamily is an "
                    + "optional declared hint: use BUILD or TEST for validation intent and DIFF only for read-only "
                    + "final diff inspection; trusted risk and authorization never depend on the hint. System git "
                    + "and gh commands use the same risk classification, approval, workspace, sandbox, network, and "
                    + "audit controls as other execution commands; delivery intent is completion metadata, not "
                    + "command authorization.";
        }
        if (name.equals("file_read")) {
            return "Read one bounded text window from a workspace file. Continue with nextCursor only when hasMore "
                    + "is true; the cursor detects path reuse and file changes, so large files are never loaded in "
                    + "full by default. On FILE_CURSOR_STALE, restart once without the old cursor; sensitive paths "
                    + "require user action and must not be copied or renamed.";
        }
        if (name.equals("file_create")) {
            return "Create a new file only when the target is absent. If the target exists, use file_write for an "
                    + "intentional full replacement or file_patch for a bounded edit.";
        }
        if (name.equals("file_write")) {
            return "Replace the complete contents of an existing file with revision and content-hash protection; "
                    + "if the target is absent, creates the file atomically. Prefer file_patch for bounded edits.";
        }
        if (name.equals("file_patch")) {
            return "Apply a bounded, non-atomic context patch to up to 100 files in one authorized directory using host absolute paths. Use "
                    + "*** Begin Patch / *** End Patch with Add File or Update File sections; use file_delete and "
                    + "file_move for those operations. An Update File hunk starts with @@ and may include @@ <text> as an "
                    + "optional navigation hint. The old and context lines determine the edit: a unique exact match applies "
                    + "even when the hint is stale; repeated matches must be reduced to one by a unique exact hint or the patch is ambiguous. "
                    + "A pure insertion needs a unique hint, an exact context line, or *** End of File. "
                    + "All files are preflighted with optimistic checks before the "
                    + "first write. Cross-directory patches are rejected. A commit-time failure reports its committed prefix "
                    + "and requires a fresh read before regenerating the patch.";
        }
        if (name.equals("workspace_attach")) {
            return "Request one additional existing local directory for this Coding Agent registry. Supply an "
                    + "absolute host path and explicit read or develop mode. The user "
                    + "must approve the exact directory and mode before it becomes available in the scope; "
                    + "successful attachments are revalidated before restoration and returned as a path-redacted workspaceRef.";
        }
        if (name.equals(ProjectWorktreeToolOperations.TOOL_NAME)) {
            return "Create one managed Git worktree from an active executable workspace after exact user approval. "
                    + "The immutable base commit, new branch, managed target name, and delivery intent "
                    + "are approved together; no arbitrary host target path is accepted.";
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
            case "file_list" -> {
                path(properties, required, "path");
                properties.put("recursive", Map.of("type", "boolean"));
                properties.put("maxDepth", Map.of("type", "integer", "minimum", 1, "maximum", 32));
            }
            case "file_stat", "file_delete" -> path(properties, required, "path");
            case "file_read" -> {
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
            case "file_search" -> {
                path(properties, required, "path");
                string(properties, required, "query");
                properties.put("glob", Map.of("type", "string"));
                properties.put("maxResults", Map.of("type", "integer", "minimum", 1, "maximum", 1000));
            }
            case "file_create", "file_write", "file_diff" -> {
                path(properties, required, "path");
                string(properties, required, "content");
            }
            case "file_move" -> {
                path(properties, required, "source");
                path(properties, required, "destination");
            }
            case "file_patch" -> {
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
                                "Context patch beginning with *** Begin Patch and ending with *** End Patch, declaring host absolute paths for each file. "
                                        + "Each Update File hunk begins with @@; @@ <text> adds an optional navigation hint. Old/context lines must have a unique exact match, or a unique exact hint must reduce repeated matches to one; otherwise the patch is ambiguous. A pure insertion needs a unique hint, an exact context line, or *** End of File."));
                required.add("patch");
            }
            case "workspace_attach" -> {
                properties.put(
                        "path",
                        Map.of(
                                "type",
                                "string",
                                "minLength",
                                1,
                                "maxLength",
                                4096,
                                "description",
                                "Absolute path of the user-requested existing local directory."));
                properties.put("mode", Map.of("type", "string", "enum", List.of("read", "develop")));
                required.add("path");
                required.add("mode");
            }
            case ProjectWorktreeToolOperations.TOOL_NAME -> {
                properties.put("sourceWorkspaceRef", Map.of("type", "string", "minLength", 1, "maxLength", 256));
                properties.put("baseCommit", Map.of("type", "string", "minLength", 7, "maxLength", 64));
                properties.put("branchName", Map.of("type", "string", "minLength", 1, "maxLength", 240));
                properties.put("targetName", Map.of("type", "string", "minLength", 1, "maxLength", 80));
                properties.put(
                        "deliveryIntent", Map.of("type", "string", "enum", List.of("local-change", "pull-request")));
                required.addAll(
                        List.of("sourceWorkspaceRef", "baseCommit", "branchName", "targetName", "deliveryIntent"));
            }
            case "execution_run" -> {
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
                properties.put(
                        "workspaceRef",
                        Map.of(
                                "type",
                                "string",
                                "minLength",
                                1,
                                "maxLength",
                                256,
                                "description",
                                "Opaque reference of an active root from the workspace registry."));
                properties.put(
                        "relativeWorkdir",
                        Map.of(
                                "type",
                                "string",
                                "minLength",
                                1,
                                "maxLength",
                                4096,
                                "description",
                                "Canonical directory below workspaceRef. Use . for the root; absolute paths, UNC paths, drive paths, and traversal are forbidden."));
                required.add("workspaceRef");
                required.add("relativeWorkdir");
                properties.put("timeoutMillis", Map.of("type", "integer", "minimum", 1, "maximum", 1800000));
                properties.put(
                        "expectedExitCodes",
                        Map.of(
                                "type",
                                "array",
                                "minItems",
                                1,
                                "maxItems",
                                8,
                                "uniqueItems",
                                true,
                                "items",
                                Map.of("type", "integer", "minimum", 0, "maximum", 255),
                                "description",
                                "Allowed normal process exit codes. Defaults to [0]; when supplied it must include 0. "
                                        + "Never use this for timeouts, cancellation, or an unknown outcome."));
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
        if (name.equals("file_read")) {
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
        if (name.equals("execution_run")) {
            var properties = new LinkedHashMap<String, Object>();
            properties.put("toolCallId", Map.of("type", "string"));
            properties.put("executionId", Map.of("type", "string"));
            properties.put("status", Map.of("type", "string"));
            properties.put("processState", Map.of("type", "string"));
            properties.put("exitCode", Map.of("type", "integer"));
            properties.put("expectedExitCodes", Map.of("type", "array", "items", Map.of("type", "integer")));
            properties.put("semanticOutcome", Map.of("type", "string"));
            properties.put("semanticReasonCode", Map.of("type", "string"));
            properties.put("semanticInterpreterVersion", Map.of("type", "string"));
            properties.put("commandOutcomeCode", Map.of("type", "string"));
            properties.put("runtimeOutcome", Map.of("type", "string", "enum", List.of("OUTCOME_UNKNOWN")));
            properties.put("reconcileStatus", Map.of("type", "string"));
            properties.put("reconcileReason", Map.of("type", "string"));
            properties.put("replayAllowed", Map.of("type", "boolean"));
            properties.put("output", Map.of("type", "string"));
            properties.put("truncated", Map.of("type", "boolean"));
            properties.put("outputRef", Map.of("type", "string"));
            properties.put("outputRefs", Map.of("type", "array", "items", Map.of("type", "string")));
            properties.put("durationMillis", Map.of("type", "integer", "minimum", 0));
            properties.put("observedProcessCount", Map.of("type", "integer", "minimum", 0));
            properties.put("failureCode", Map.of("type", "string"));
            properties.put("failureDetail", Map.of("type", "string"));
            properties.put("failureCategory", Map.of("type", "string"));
            properties.put("stableFailureCode", Map.of("type", "string"));
            properties.put("resourceClass", Map.of("type", "string"));
            properties.put("failureAction", Map.of("type", "string"));
            properties.put("failureActionCode", Map.of("type", "string"));
            properties.put("operationFamily", Map.of("type", "string"));
            properties.put("effectiveOperationFamily", Map.of("type", "string"));
            properties.put("commandTarget", Map.of("type", "string"));
            properties.put("commandRisk", Map.of("type", "string"));
            properties.put("effectiveRisk", Map.of("type", "string"));
            properties.put("commandOperation", Map.of("type", "string"));
            properties.put("commandClassificationReason", Map.of("type", "string"));
            properties.put("riskResolverVersion", Map.of("type", "string"));
            properties.put("riskResolutionCode", Map.of("type", "string"));
            properties.put("riskAction", Map.of("type", "string"));
            properties.put("operationHintCode", Map.of("type", "string"));
            properties.put("deliveryAction", Map.of("type", "string"));
            properties.put("deliveryVerification", Map.of("type", "string"));
            properties.put("deliveryRepositoryScopeDigest", Map.of("type", "string"));
            properties.put("deliveryEvidenceCode", Map.of("type", "string"));
            properties.put("deliveryEvidenceRef", Map.of("type", "string"));
            properties.put("outputBudgetFamily", Map.of("type", "string"));
            properties.put("outputBudgetBytesPerChannel", Map.of("type", "integer", "minimum", 1));
            properties.put("modelOutputBudgetBytes", Map.of("type", "integer", "minimum", 1));
            properties.put("modelOutputBudgetLines", Map.of("type", "integer", "minimum", 1));
            properties.put("diffFileCount", Map.of("type", "integer", "minimum", 0));
            properties.put("diffHunkCount", Map.of("type", "integer", "minimum", 0));
            properties.put("diffCountsComplete", Map.of("type", "boolean"));
            properties.put("diffSummary", Map.of("type", "string"));
            properties.put("diffArtifactRef", Map.of("type", "string"));
            properties.put("validationEvidence", Map.of("type", "object", "additionalProperties", true));
            properties.put("validationAttemptRef", Map.of("type", "string"));
            properties.put("sandboxProfileDigest", Map.of("type", "string"));
            properties.put("scratchSpecDigest", Map.of("type", "string"));
            properties.put("scratchProvisioned", Map.of("type", "boolean"));
            properties.put("scratchCleanupFailed", Map.of("type", "boolean"));
            return Map.of(
                    "$schema",
                    ToolSchema.DRAFT_2020_12,
                    "type",
                    "object",
                    "properties",
                    Map.copyOf(properties),
                    "required",
                    List.of("executionId", "status", "processState", "output", "truncated", "durationMillis"),
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
                        "Host absolute path within an authorized directory. Relative paths and root aliases are not allowed."));
        required.add(name);
    }

    private static void string(Map<String, Object> properties, List<String> required, String name) {
        properties.put(name, Map.of("type", "string"));
        required.add(name);
    }
}
