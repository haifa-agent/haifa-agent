package io.haifa.agent.mcp;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.mcp.client.McpConnectionIdentity;
import io.haifa.agent.mcp.config.McpConnectionPolicy;
import io.haifa.agent.mcp.config.McpCredentialInjection;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.config.McpServerId;
import io.haifa.agent.mcp.config.McpToolImportPolicy;
import io.haifa.agent.mcp.config.StreamableHttpDefinition;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class McpTestFixtures {
    static final TenantRef TENANT = new TenantRef("tenant-a");
    static final PrincipalRef PRINCIPAL = new PrincipalRef("alice", "user");
    static final McpConnectionIdentity IDENTITY = new McpConnectionIdentity(TENANT, PRINCIPAL);

    private McpTestFixtures() {}

    static McpServerDefinition httpServer(URI endpoint, Set<String> allowedTools) {
        return httpServer(endpoint, allowedTools, Duration.ofSeconds(3), 1024 * 1024, 16 * 1024);
    }

    static McpServerDefinition httpServer(URI endpoint, Set<String> allowedTools, McpProtocolProfile protocol) {
        return httpServer(endpoint, allowedTools, protocol, Duration.ofSeconds(3), 1024 * 1024, 16 * 1024);
    }

    static McpServerDefinition httpServer(
            URI endpoint, Set<String> allowedTools, Duration requestTimeout, int maxBodyBytes, int maxHeaderBytes) {
        return httpServer(
                endpoint,
                allowedTools,
                McpProtocolProfile.FIXED_2025_11_25,
                requestTimeout,
                maxBodyBytes,
                maxHeaderBytes);
    }

    private static McpServerDefinition httpServer(
            URI endpoint,
            Set<String> allowedTools,
            McpProtocolProfile protocol,
            Duration requestTimeout,
            int maxBodyBytes,
            int maxHeaderBytes) {
        return McpServerDefinition.create(
                new McpServerId("utility"),
                "Utility",
                true,
                protocol,
                new StreamableHttpDefinition(
                        endpoint,
                        true,
                        Set.of(StreamableHttpDefinition.origin(endpoint)),
                        Duration.ofSeconds(2),
                        requestTimeout,
                        Duration.ofSeconds(10),
                        maxBodyBytes,
                        maxHeaderBytes),
                new McpToolImportPolicy(
                        allowedTools,
                        Set.of(),
                        "utility",
                        Map.of("time_now", ToolRisk.LOW),
                        Map.of("time_now", ToolIdempotency.IDEMPOTENT),
                        Map.of("time_now", Set.of(ToolSideEffect.NETWORK_ACCESS)),
                        Map.of("time_now", ToolApprovalRequirement.NEVER)),
                new McpConnectionPolicy(
                        Duration.ofSeconds(2), requestTimeout, Duration.ofSeconds(10), Duration.ofSeconds(2), 1),
                List.of(),
                "1.0.0");
    }

    static McpServerDefinition withDiscoveryCredentials(
            McpServerDefinition server, List<McpCredentialInjection> discoveryCredentials) {
        return McpServerDefinition.create(
                server.serverId(),
                server.displayName(),
                server.enabled(),
                server.protocol(),
                server.transport(),
                server.importPolicy(),
                server.connectionPolicy(),
                discoveryCredentials,
                server.bindingVersion());
    }
}
