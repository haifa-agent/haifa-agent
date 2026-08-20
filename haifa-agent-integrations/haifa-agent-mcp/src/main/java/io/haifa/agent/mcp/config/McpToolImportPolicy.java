package io.haifa.agent.mcp.config;

import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record McpToolImportPolicy(
        Set<String> allowedTools,
        Set<String> deniedTools,
        String aliasNamespace,
        Map<String, ToolRisk> riskOverrides,
        Map<String, ToolIdempotency> idempotencyOverrides,
        Map<String, Set<ToolSideEffect>> sideEffectOverrides,
        Map<String, ToolApprovalRequirement> approvalOverrides,
        Map<String, String> aliasOverrides) {
    public McpToolImportPolicy(
            Set<String> allowedTools,
            Set<String> deniedTools,
            String aliasNamespace,
            Map<String, ToolRisk> riskOverrides,
            Map<String, ToolIdempotency> idempotencyOverrides,
            Map<String, Set<ToolSideEffect>> sideEffectOverrides,
            Map<String, ToolApprovalRequirement> approvalOverrides) {
        this(
                allowedTools,
                deniedTools,
                aliasNamespace,
                riskOverrides,
                idempotencyOverrides,
                sideEffectOverrides,
                approvalOverrides,
                Map.of());
    }

    public McpToolImportPolicy {
        allowedTools = Set.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
        deniedTools = Set.copyOf(Objects.requireNonNull(deniedTools, "deniedTools"));
        if (aliasNamespace == null || !aliasNamespace.matches("[a-z][a-z0-9_]{0,31}")) {
            throw new IllegalArgumentException("alias namespace is invalid");
        }
        riskOverrides = Map.copyOf(Objects.requireNonNull(riskOverrides, "riskOverrides"));
        idempotencyOverrides = Map.copyOf(Objects.requireNonNull(idempotencyOverrides, "idempotencyOverrides"));
        sideEffectOverrides = Map.copyOf(Objects.requireNonNull(sideEffectOverrides, "sideEffectOverrides"));
        approvalOverrides = Map.copyOf(Objects.requireNonNull(approvalOverrides, "approvalOverrides"));
        aliasOverrides = Map.copyOf(Objects.requireNonNull(aliasOverrides, "aliasOverrides"));
        if (!allowedTools.containsAll(aliasOverrides.keySet())) {
            throw new IllegalArgumentException("alias overrides contain a tool outside the allowlist");
        }
        var aliases = new java.util.HashSet<ToolAlias>();
        for (String remoteToolName : allowedTools) {
            String alias = aliasOverrides.getOrDefault(remoteToolName, aliasNamespace + "_" + remoteToolName);
            if (!aliases.add(new ToolAlias(alias))) {
                throw new IllegalArgumentException("effective MCP tool aliases must be unique");
            }
        }
    }

    public boolean permits(String remoteToolName) {
        return !deniedTools.contains(remoteToolName)
                && !allowedTools.isEmpty()
                && allowedTools.contains(remoteToolName);
    }

    public ToolAlias aliasFor(String remoteToolName) {
        if (!permits(remoteToolName)) {
            throw new IllegalArgumentException("remote tool is not approved by the local allowlist");
        }
        return new ToolAlias(aliasOverrides.getOrDefault(remoteToolName, aliasNamespace + "_" + remoteToolName));
    }
}
