package io.haifa.agent.mcp.tool;

import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.config.StreamableHttpDefinition;
import io.haifa.agent.mcp.protocol.McpRemoteTool;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolDefinitionHasher;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class McpToolDefinitionMapper {
    private final ToolDefinitionHasher hasher;
    private final McpToolBindingStore bindings;

    public McpToolDefinitionMapper(ToolDefinitionHasher hasher, McpToolBindingStore bindings) {
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
    }

    public McpToolImportCandidate map(
            McpServerDefinition server, String negotiatedProtocolVersion, McpRemoteTool remote) {
        String remoteDigest = remote.remoteDefinitionDigest();
        List<McpToolImportDiagnostic> diagnostics = new ArrayList<>();
        if (!remote.name().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            diagnostics.add(new McpToolImportDiagnostic("MCP_TOOL_NAME_INVALID", "remote tool name is invalid"));
        }
        if (!server.importPolicy().permits(remote.name())) {
            diagnostics.add(new McpToolImportDiagnostic(
                    "MCP_TOOL_REVIEW_REQUIRED", "remote tool is not approved by the local allowlist"));
        }
        Map<String, Object> input = normalizeSchema(remote.inputSchema(), "input", diagnostics);
        boolean genericOutput = remote.outputSchema().isEmpty();
        Map<String, Object> output = genericOutput
                ? Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", true)
                : normalizeSchema(remote.outputSchema(), "output", diagnostics);
        if (!diagnostics.isEmpty()) {
            return new McpToolImportCandidate(
                    remote.name(),
                    remoteDigest,
                    false,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    diagnostics);
        }
        ToolRisk risk = server.importPolicy().riskOverrides().getOrDefault(remote.name(), ToolRisk.HIGH);
        ToolIdempotency idempotency =
                server.importPolicy().idempotencyOverrides().getOrDefault(remote.name(), ToolIdempotency.UNKNOWN);
        Set<ToolSideEffect> sideEffects = server.importPolicy()
                .sideEffectOverrides()
                .getOrDefault(
                        remote.name(), Set.of(ToolSideEffect.NETWORK_ACCESS, ToolSideEffect.EXTERNAL_SYSTEM_MUTATION));
        ToolApprovalRequirement approval =
                server.importPolicy().approvalOverrides().getOrDefault(remote.name(), ToolApprovalRequirement.ALWAYS);
        Set<String> networkHosts = server.transport() instanceof StreamableHttpDefinition http
                ? Set.of(http.endpoint().getHost().toLowerCase())
                : Set.of();
        var definition = new ToolDefinition(
                new ToolName("mcp." + server.serverId().value() + "." + remote.name()),
                new SemanticVersion(server.bindingVersion()),
                new ToolProviderId("mcp." + server.serverId().value()),
                remote.title(),
                remote.description(),
                new ToolSchema(
                        "mcp." + server.serverId().value() + "." + remote.name() + ".input",
                        server.bindingVersion(),
                        input),
                new ToolSchema(
                        "mcp." + server.serverId().value() + "." + remote.name() + ".output",
                        server.bindingVersion(),
                        output),
                ToolExecutionMode.REMOTE_PROVIDER,
                true,
                server.connectionPolicy().requestTimeout(),
                "mcp-server:" + server.serverId().value(),
                idempotency,
                risk,
                sideEffects,
                new ToolResourceRequirements(Set.of(), networkHosts, Set.of()),
                server.discoveryCredentials().stream()
                        .map(injection -> injection.requirement())
                        .toList(),
                approval,
                "mcp:" + server.serverId().value() + ":" + remoteDigest
                        + (genericOutput ? ":generic-output-low-confidence" : ""),
                false,
                Set.of("mcp", "remote"));
        var snapshot = McpToolBindingSnapshot.create(
                server,
                remote.name(),
                remoteDigest,
                negotiatedProtocolVersion,
                definition.credentialRequirements(),
                hasher.hash(definition));
        bindings.put(snapshot);
        return new McpToolImportCandidate(
                remote.name(),
                remoteDigest,
                true,
                Optional.of(new ToolAlias(server.importPolicy().aliasNamespace() + "_" + remote.name())),
                Optional.of(definition),
                Optional.of(snapshot),
                List.of());
    }

    private static Map<String, Object> normalizeSchema(
            Map<String, Object> source, String kind, List<McpToolImportDiagnostic> diagnostics) {
        Map<String, Object> schema = new LinkedHashMap<>(source);
        Object dialect = schema.get("$schema");
        if (dialect != null && !ToolSchema.DRAFT_2020_12.equals(dialect)) {
            diagnostics.add(new McpToolImportDiagnostic(
                    "MCP_SCHEMA_DIALECT_UNSUPPORTED", kind + " schema is not Draft 2020-12"));
        } else {
            schema.putIfAbsent("$schema", ToolSchema.DRAFT_2020_12);
        }
        inspectSchema(schema, kind, diagnostics, 0, new int[] {0, 0});
        return Map.copyOf(schema);
    }

    private static void inspectSchema(
            Object value, String kind, List<McpToolImportDiagnostic> diagnostics, int depth, int[] budget) {
        if (depth > 64 || ++budget[0] > 4096) {
            addOnce(diagnostics, "MCP_SCHEMA_TOO_LARGE", kind + " schema exceeds structural limits");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("pattern")) {
                addOnce(diagnostics, "MCP_SCHEMA_PATTERN_UNSUPPORTED", kind + " schema contains pattern");
            }
            Object reference = map.get("$ref");
            if (reference instanceof String text && !text.startsWith("#")) {
                addOnce(diagnostics, "MCP_SCHEMA_REMOTE_REF_UNSUPPORTED", kind + " schema contains a remote reference");
            }
            map.forEach((key, element) -> {
                budget[1] = Math.addExact(budget[1], String.valueOf(key).length());
                inspectSchema(element, kind, diagnostics, depth + 1, budget);
            });
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(element -> inspectSchema(element, kind, diagnostics, depth + 1, budget));
        } else if (value instanceof String text) {
            budget[1] = Math.addExact(budget[1], text.length());
        }
        if (budget[1] > 1_048_576) {
            addOnce(diagnostics, "MCP_SCHEMA_TOO_LARGE", kind + " schema exceeds the text budget");
        }
    }

    private static void addOnce(List<McpToolImportDiagnostic> diagnostics, String code, String message) {
        if (diagnostics.stream().noneMatch(diagnostic -> diagnostic.code().equals(code))) {
            diagnostics.add(new McpToolImportDiagnostic(code, message));
        }
    }
}
