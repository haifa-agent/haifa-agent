package io.haifa.agent.mcp.config;

import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.util.Map;
import java.util.Set;

/** Conservative example profile for utility tools exposed to a Coding Agent. */
public final class CodingAgentMcpProfile {
    private static final Set<String> LOCAL_PURE_TOOLS = Set.of("calculate", "time_convert", "time_now", "unit_convert");
    private static final Set<String> NETWORK_READ_TOOLS = Set.of(
            "microsoft_code_sample_search",
            "microsoft_docs_fetch",
            "microsoft_docs_search",
            "wikipedia_search",
            "wikipedia_summary");

    private CodingAgentMcpProfile() {}

    public static McpToolImportPolicy utilityPolicy() {
        var allowed = new java.util.HashSet<>(LOCAL_PURE_TOOLS);
        allowed.addAll(NETWORK_READ_TOOLS);
        Map<String, ToolRisk> risk = allowed.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(name -> name, ignored -> ToolRisk.LOW));
        Map<String, ToolIdempotency> idempotency = allowed.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(name -> name, ignored -> ToolIdempotency.PURE));
        Map<String, Set<ToolSideEffect>> sideEffects = NETWORK_READ_TOOLS.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        name -> name, ignored -> Set.of(ToolSideEffect.NETWORK_ACCESS)));
        Map<String, ToolApprovalRequirement> approvals = NETWORK_READ_TOOLS.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        name -> name, ignored -> ToolApprovalRequirement.POLICY));
        return new McpToolImportPolicy(allowed, Set.of(), "utility", risk, idempotency, sideEffects, approvals);
    }
}
