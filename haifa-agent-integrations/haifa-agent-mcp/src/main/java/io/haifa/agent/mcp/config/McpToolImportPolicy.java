package io.haifa.agent.mcp.config;

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
        Map<String, ToolApprovalRequirement> approvalOverrides) {
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
    }

    public boolean permits(String remoteToolName) {
        return !deniedTools.contains(remoteToolName)
                && !allowedTools.isEmpty()
                && allowedTools.contains(remoteToolName);
    }
}
