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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Explicit loopback MCP discovery and provider lifecycle for a reviewed Tool allowlist. */
public final class PersonalMcpPlatform implements AutoCloseable {
    public static final String REMOTE_TOOL = "echo";
    public static final String LOCAL_ALIAS = "personal_mcp_echo";
    private final McpConnectionManager connections;
    private final List<McpToolCatalogContribution> contributions;

    private PersonalMcpPlatform(McpConnectionManager connections, List<McpToolCatalogContribution> contributions) {
        this.connections = connections;
        this.contributions = List.copyOf(contributions);
    }

    public static PersonalMcpPlatform connect(
            PersonalMcpConfiguration configuration, TenantRef tenant, PrincipalRef principal, Clock clock) {
        requireLoopback(configuration.endpoint());
        Set<String> allowedTools = configuration.allowedTools();
        var policy = new McpToolImportPolicy(
                allowedTools,
                Set.of(),
                configuration.aliasNamespace(),
                values(allowedTools, ToolRisk.LOW),
                values(allowedTools, ToolIdempotency.IDEMPOTENT),
                values(allowedTools, Set.<ToolSideEffect>of()),
                values(allowedTools, ToolApprovalRequirement.NEVER));
        var server = McpServerDefinition.create(
                new McpServerId(configuration.serverId()),
                configuration.displayName(),
                true,
                McpProtocolProfile.FIXED_2025_11_25,
                new StreamableHttpDefinition(
                        configuration.endpoint(),
                        true,
                        Set.of(StreamableHttpDefinition.origin(configuration.endpoint())),
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
                    64,
                    256 * 1024,
                    Duration.ofSeconds(15));
            List<McpToolImportCandidate> candidates =
                    discovery.discover(server.serverId(), new McpDiscoveryContext(tenant, principal, List.of()));
            Map<String, McpToolImportCandidate> reviewed = candidates.stream()
                    .filter(candidate -> allowedTools.contains(candidate.remoteName()))
                    .collect(Collectors.toMap(McpToolImportCandidate::remoteName, Function.identity()));
            for (String tool : allowedTools) {
                McpToolImportCandidate candidate = reviewed.get(tool);
                if (candidate == null) {
                    throw new IllegalStateException("required MCP Tool was not discovered: " + tool);
                }
                if (!candidate.enabled()) {
                    throw new IllegalStateException("required MCP Tool failed local review: " + tool);
                }
            }
            var provider =
                    new McpToolProvider(server.serverId(), bindings, connections, new McpContentMapper(redactor));
            List<McpToolCatalogContribution> contributions = reviewed.values().stream()
                    .sorted(Comparator.comparing(McpToolImportCandidate::remoteName))
                    .map(candidate -> McpToolCatalogContribution.from(candidate, provider))
                    .toList();
            return new PersonalMcpPlatform(connections, contributions);
        } catch (RuntimeException exception) {
            connections.close();
            throw exception;
        }
    }

    public List<McpToolCatalogContribution> contributions() {
        return contributions;
    }

    public Set<String> aliases() {
        return contributions.stream()
                .map(contribution -> contribution.alias().value())
                .collect(Collectors.toUnmodifiableSet());
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

    private static <T> Map<String, T> values(Set<String> keys, T value) {
        return keys.stream().collect(Collectors.toUnmodifiableMap(Function.identity(), ignored -> value));
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
