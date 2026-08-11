package io.haifa.example.runtime.mcp;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.credential.api.CredentialBroker;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.credential.api.CredentialOperationRequest;
import io.haifa.agent.credential.api.CredentialRequest;
import io.haifa.agent.credential.api.SecretRedactor;
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
import io.haifa.agent.tool.core.DefaultToolCatalog;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import io.haifa.agent.tool.core.ToolDefinitionCanonicalizer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns one reviewed Utility MCP connection and its frozen Runtime Tool catalog. */
public final class UtilityMcpRuntimePlatform implements AutoCloseable {
    public static final String REMOTE_TOOL_NAME = "unit_convert";
    public static final String LOCAL_TOOL_ALIAS = "utility_unit_convert";
    private static final McpServerId SERVER_ID = new McpServerId("utility-demo");
    private static final TenantRef TENANT = new TenantRef("local");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("local-user", "user");

    private final McpConnectionManager connections;
    private final DefaultToolCatalog catalog;

    private UtilityMcpRuntimePlatform(McpConnectionManager connections, DefaultToolCatalog catalog) {
        this.connections = connections;
        this.catalog = catalog;
    }

    public static UtilityMcpRuntimePlatform connect(URI endpoint) {
        McpServerDefinition server = serverDefinition(endpoint);
        var connections = new McpConnectionManager(List.of(server), new SdkMcpClientFactory());
        try {
            var bindings = new InMemoryMcpToolBindingStore();
            SecretRedactor redactor = new DefaultSecretRedactor();
            var discovery = new McpToolDiscoveryService(
                    connections,
                    new McpToolDefinitionMapper(new ToolDefinitionCanonicalizer(), bindings),
                    noCredentials(redactor),
                    Clock.systemUTC(),
                    4,
                    64,
                    256 * 1024,
                    Duration.ofSeconds(15));
            McpToolImportCandidate candidate =
                    discovery.discover(SERVER_ID, new McpDiscoveryContext(TENANT, PRINCIPAL, List.of())).stream()
                            .filter(item -> REMOTE_TOOL_NAME.equals(item.remoteName()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "required Utility MCP Tool was not discovered: " + REMOTE_TOOL_NAME));
            if (!candidate.enabled()) {
                throw new IllegalStateException(
                        "required Utility MCP Tool failed local review: " + REMOTE_TOOL_NAME + " "
                                + candidate.diagnostics().stream()
                                        .map(diagnostic -> diagnostic.code())
                                        .sorted()
                                        .toList());
            }

            var provider = new McpToolProvider(SERVER_ID, bindings, connections, new McpContentMapper(redactor));
            McpToolCatalogContribution contribution = McpToolCatalogContribution.from(candidate, provider);
            if (!LOCAL_TOOL_ALIAS.equals(contribution.alias().value())) {
                throw new IllegalStateException("unexpected Utility MCP Tool alias: "
                        + contribution.alias().value());
            }
            DefaultToolCatalog catalog = new ToolCatalogBuilder()
                    .register(
                            contribution.alias(),
                            contribution.definition(),
                            contribution.providerBindingReference(),
                            contribution.provider())
                    .freeze();
            return new UtilityMcpRuntimePlatform(connections, catalog);
        } catch (RuntimeException exception) {
            connections.close();
            throw exception;
        }
    }

    public static McpServerDefinition serverDefinition(URI endpoint) {
        var importPolicy = new McpToolImportPolicy(
                Set.of(REMOTE_TOOL_NAME),
                Set.of(),
                "utility",
                Map.of(REMOTE_TOOL_NAME, ToolRisk.LOW),
                Map.of(REMOTE_TOOL_NAME, ToolIdempotency.PURE),
                Map.of(REMOTE_TOOL_NAME, Set.of()),
                Map.of(REMOTE_TOOL_NAME, ToolApprovalRequirement.NEVER));
        var transport = new StreamableHttpDefinition(
                endpoint,
                true,
                Set.of(StreamableHttpDefinition.origin(endpoint)),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                256 * 1024,
                16 * 1024);
        return McpServerDefinition.create(
                SERVER_ID,
                "Haifa Utility MCP",
                true,
                McpProtocolProfile.FIXED_2025_11_25,
                transport,
                importPolicy,
                new McpConnectionPolicy(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(1),
                        1),
                List.of(),
                "1.0.0");
    }

    public DefaultToolCatalog catalog() {
        return catalog;
    }

    @Override
    public void close() {
        connections.close();
    }

    private static CredentialBroker noCredentials(SecretRedactor redactor) {
        return new CredentialBroker() {
            @Override
            public CredentialLease issue(CredentialRequest request) {
                throw new IllegalStateException("Utility MCP credentials are not configured");
            }

            @Override
            public CredentialLease issue(CredentialOperationRequest request) {
                throw new IllegalStateException("Utility MCP credentials are not configured");
            }

            @Override
            public SecretRedactor redactor() {
                return redactor;
            }
        };
    }
}
