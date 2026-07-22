package io.haifa.agent.cli;

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
import io.haifa.agent.mcp.config.CodingAgentMcpProfile;
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
import io.haifa.agent.tool.core.ToolDefinitionCanonicalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Discovers and owns the MCP providers configured for one CLI process. */
final class CliMcpPlatform implements AutoCloseable {
    private static final TenantRef LOCAL_TENANT = new TenantRef("local");
    private final McpConnectionManager connections;
    private final List<McpToolCatalogContribution> contributions;

    private CliMcpPlatform(McpConnectionManager connections, List<McpToolCatalogContribution> contributions) {
        this.connections = connections;
        this.contributions = List.copyOf(contributions);
    }

    static CliMcpPlatform connect(List<CliConfiguration.McpServer> configured, PrincipalRef principal) {
        List<McpServerDefinition> servers =
                configured.stream().map(CliMcpPlatform::definition).toList();
        var connections = new McpConnectionManager(servers, new SdkMcpClientFactory());
        if (servers.isEmpty()) return new CliMcpPlatform(connections, List.of());

        var bindings = new InMemoryMcpToolBindingStore();
        SecretRedactor redactor = new DefaultSecretRedactor();
        var mapper = new McpToolDefinitionMapper(new ToolDefinitionCanonicalizer(), bindings);
        var discovery = new McpToolDiscoveryService(
                connections,
                mapper,
                noCredentialBroker(redactor),
                Clock.systemUTC(),
                32,
                256,
                4 * 1024 * 1024,
                Duration.ofSeconds(60));
        List<McpToolCatalogContribution> contributions = new ArrayList<>();
        try {
            for (int index = 0; index < servers.size(); index++) {
                McpServerDefinition server = servers.get(index);
                CliConfiguration.McpServer source = configured.get(index);
                List<McpToolImportCandidate> candidates = discovery.discover(
                        server.serverId(), new McpDiscoveryContext(LOCAL_TENANT, principal, List.of()));
                requireConfiguredTools(source, candidates);
                var provider =
                        new McpToolProvider(server.serverId(), bindings, connections, new McpContentMapper(redactor));
                candidates.stream()
                        .filter(McpToolImportCandidate::enabled)
                        .map(candidate -> McpToolCatalogContribution.from(candidate, provider))
                        .forEach(contributions::add);
            }
            return new CliMcpPlatform(connections, contributions);
        } catch (RuntimeException exception) {
            connections.close();
            throw exception;
        }
    }

    List<McpToolCatalogContribution> contributions() {
        return contributions;
    }

    @Override
    public void close() {
        connections.close();
    }

    private static McpServerDefinition definition(CliConfiguration.McpServer source) {
        var serverId = new McpServerId(source.id());
        var transport = new StreamableHttpDefinition(
                source.endpoint(),
                source.allowLoopbackHttp(),
                Set.of(StreamableHttpDefinition.origin(source.endpoint())),
                source.connectTimeout(),
                source.requestTimeout(),
                source.idleTimeout(),
                source.maxBodyBytes(),
                source.maxHeaderBytes());
        return McpServerDefinition.create(
                serverId,
                source.displayName(),
                true,
                McpProtocolProfile.FIXED_2025_11_25,
                transport,
                importPolicy(source),
                new McpConnectionPolicy(
                        source.connectTimeout(),
                        source.requestTimeout(),
                        source.idleTimeout(),
                        Duration.ofSeconds(5),
                        source.maxReconnectAttempts()),
                List.of(),
                "1.0.0");
    }

    private static McpToolImportPolicy importPolicy(CliConfiguration.McpServer source) {
        if ("conservative".equals(source.policyProfile())) {
            return new McpToolImportPolicy(
                    source.allowedTools(), Set.of(), source.aliasNamespace(), Map.of(), Map.of(), Map.of(), Map.of());
        }
        McpToolImportPolicy reviewed = CodingAgentMcpProfile.utilityPolicy();
        if (!reviewed.allowedTools().containsAll(source.allowedTools())) {
            Set<String> unsupported = new HashSet<>(source.allowedTools());
            unsupported.removeAll(reviewed.allowedTools());
            throw new IllegalArgumentException("MCP utility profile does not approve configured tools: "
                    + unsupported.stream().sorted().toList());
        }
        return new McpToolImportPolicy(
                source.allowedTools(),
                Set.of(),
                source.aliasNamespace(),
                selected(reviewed.riskOverrides(), source.allowedTools()),
                selected(reviewed.idempotencyOverrides(), source.allowedTools()),
                selected(reviewed.sideEffectOverrides(), source.allowedTools()),
                selected(reviewed.approvalOverrides(), source.allowedTools()));
    }

    private static <T> Map<String, T> selected(Map<String, T> values, Set<String> names) {
        return values.entrySet().stream()
                .filter(entry -> names.contains(entry.getKey()))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static void requireConfiguredTools(
            CliConfiguration.McpServer server, List<McpToolImportCandidate> candidates) {
        Map<String, McpToolImportCandidate> byName = candidates.stream()
                .collect(Collectors.toUnmodifiableMap(McpToolImportCandidate::remoteName, Function.identity()));
        List<String> failures = server.allowedTools().stream()
                .sorted()
                .filter(name -> !byName.containsKey(name) || !byName.get(name).enabled())
                .map(name -> {
                    McpToolImportCandidate candidate = byName.get(name);
                    if (candidate == null) return name + " (not discovered)";
                    return name + " ("
                            + candidate.diagnostics().stream()
                                    .map(diagnostic -> diagnostic.code())
                                    .sorted()
                                    .collect(Collectors.joining(","))
                            + ")";
                })
                .toList();
        if (!failures.isEmpty()) {
            throw new IllegalArgumentException(
                    "MCP server " + server.id() + " could not enable configured tools: " + failures);
        }
    }

    private static CredentialBroker noCredentialBroker(SecretRedactor redactor) {
        return new CredentialBroker() {
            @Override
            public CredentialLease issue(CredentialRequest request) {
                throw new IllegalStateException("CLI MCP credentials are not configured");
            }

            @Override
            public CredentialLease issue(CredentialOperationRequest request) {
                throw new IllegalStateException("CLI MCP credentials are not configured");
            }

            @Override
            public SecretRedactor redactor() {
                return redactor;
            }
        };
    }
}
