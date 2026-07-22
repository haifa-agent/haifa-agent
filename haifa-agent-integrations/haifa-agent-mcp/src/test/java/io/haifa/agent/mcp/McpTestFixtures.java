package io.haifa.agent.mcp;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.credential.api.CredentialReference;
import io.haifa.agent.mcp.client.McpConnectionIdentity;
import io.haifa.agent.mcp.config.McpConnectionPolicy;
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

    static McpServerDefinition httpServer(
            URI endpoint, Set<String> allowedTools, Duration requestTimeout, int maxBodyBytes, int maxHeaderBytes) {
        return McpServerDefinition.create(
                new McpServerId("utility"),
                "Utility",
                true,
                McpProtocolProfile.FIXED_2025_11_25,
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

    static CredentialLease lease(String reference, String secret) {
        return new CredentialLease() {
            private boolean closed;

            @Override
            public CredentialReference reference() {
                return new CredentialReference(reference);
            }

            @Override
            public Instant expiresAt() {
                return Instant.parse("2030-01-01T00:00:00Z");
            }

            @Override
            public boolean isClosed() {
                return closed;
            }

            @Override
            public <T> T use(io.haifa.agent.credential.api.SecretFunction<T> action) {
                if (closed) throw new IllegalStateException("closed");
                return action.apply(secret.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }
}
