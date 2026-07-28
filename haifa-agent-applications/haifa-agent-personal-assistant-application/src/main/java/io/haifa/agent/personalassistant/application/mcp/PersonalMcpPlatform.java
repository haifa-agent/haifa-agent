package io.haifa.agent.personalassistant.application.mcp;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.credential.api.CredentialBroker;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.credential.api.CredentialOperationRequest;
import io.haifa.agent.credential.api.CredentialRequest;
import io.haifa.agent.credential.core.DefaultSecretRedactor;
import io.haifa.agent.mcp.client.McpConnectionManager;
import io.haifa.agent.mcp.client.SdkMcpClientFactory;
import io.haifa.agent.mcp.config.McpConnectionPolicy;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.config.McpServerId;
import io.haifa.agent.mcp.config.McpToolImportPolicy;
import io.haifa.agent.mcp.config.StreamableHttpDefinition;
import io.haifa.agent.mcp.tool.InMemoryMcpToolBindingStore;
import io.haifa.agent.mcp.tool.McpContentMapper;
import io.haifa.agent.mcp.tool.McpDiscoveryContext;
import io.haifa.agent.mcp.tool.McpToolCatalogContribution;
import io.haifa.agent.mcp.tool.McpToolDefinitionMapper;
import io.haifa.agent.mcp.tool.McpToolDiscoveryService;
import io.haifa.agent.mcp.tool.McpToolImportCandidate;
import io.haifa.agent.mcp.tool.McpToolProvider;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSideEffect;
import io.haifa.agent.tool.core.ToolDefinitionCanonicalizer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit loopback MCP discovery and provider lifecycle for the required deterministic echo Tool. */
public final class PersonalMcpPlatform implements AutoCloseable {
    public static final String REMOTE_TOOL = "echo";
    public static final String LOCAL_ALIAS = "personal_mcp_echo";
    private final McpConnectionManager connections;
    private final List<McpToolCatalogContribution> contributions;

    private PersonalMcpPlatform(McpConnectionManager connections, List<McpToolCatalogContribution> contributions) {
        this.connections = connections;
        this.contributions = List.copyOf(contributions);
    }

    public static PersonalMcpPlatform connect(URI endpoint, TenantRef tenant, PrincipalRef principal, Clock clock) {
        requireLoopback(endpoint);
        var policy = new McpToolImportPolicy(
                Set.of(REMOTE_TOOL),
                Set.of(),
                "personal_mcp",
                Map.of(REMOTE_TOOL, ToolRisk.LOW),
                Map.of(REMOTE_TOOL, ToolIdempotency.PURE),
                Map.of(REMOTE_TOOL, Set.<ToolSideEffect>of()),
                Map.of(REMOTE_TOOL, ToolApprovalRequirement.NEVER));
        var server = McpServerDefinition.create(
                new McpServerId("personal-local"),
                "Personal local utility",
                true,
                McpProtocolProfile.FIXED_2025_11_25,
                new StreamableHttpDefinition(
                        endpoint,
                        true,
                        Set.of(StreamableHttpDefinition.origin(endpoint)),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(30),
                        256 * 1024,
                        16 * 1024),
                policy,
                new McpConnectionPolicy(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(1),
                        1),
                List.of(),
                "1.0.0");
        var connections = new McpConnectionManager(List.of(server), new SdkMcpClientFactory());
        try {
            var bindings = new InMemoryMcpToolBindingStore();
            var redactor = new DefaultSecretRedactor();
            var discovery = new McpToolDiscoveryService(
                    connections,
                    new McpToolDefinitionMapper(new ToolDefinitionCanonicalizer(), bindings),
                    noCredentials(redactor),
                    clock,
                    4,
                    16,
                    256 * 1024,
                    Duration.ofSeconds(15));
            List<McpToolImportCandidate> candidates =
                    discovery.discover(server.serverId(), new McpDiscoveryContext(tenant, principal, List.of()));
            McpToolImportCandidate echo = candidates.stream()
                    .filter(candidate -> REMOTE_TOOL.equals(candidate.remoteName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("required MCP echo Tool was not discovered"));
            if (!echo.enabled()) {
                throw new IllegalStateException("required MCP echo Tool failed local review");
            }
            var provider =
                    new McpToolProvider(server.serverId(), bindings, connections, new McpContentMapper(redactor));
            return new PersonalMcpPlatform(connections, List.of(McpToolCatalogContribution.from(echo, provider)));
        } catch (RuntimeException exception) {
            connections.close();
            throw exception;
        }
    }

    public List<McpToolCatalogContribution> contributions() {
        return contributions;
    }

    @Override
    public void close() {
        connections.close();
    }

    private static void requireLoopback(URI endpoint) {
        String host = endpoint.getHost();
        if (!"http".equalsIgnoreCase(endpoint.getScheme())
                || host == null
                || !Set.of("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")
                        .contains(host.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("Personal MCP endpoint must be loopback HTTP");
        }
    }

    private static CredentialBroker noCredentials(DefaultSecretRedactor redactor) {
        return new CredentialBroker() {
            @Override
            public CredentialLease issue(CredentialRequest request) {
                throw new IllegalStateException("Personal local MCP does not accept credentials");
            }

            @Override
            public CredentialLease issue(CredentialOperationRequest request) {
                throw new IllegalStateException("Personal local MCP does not accept credentials");
            }

            @Override
            public io.haifa.agent.credential.api.SecretRedactor redactor() {
                return redactor;
            }
        };
    }
}
