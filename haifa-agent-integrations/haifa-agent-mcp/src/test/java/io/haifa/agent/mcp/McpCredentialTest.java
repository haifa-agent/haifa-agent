package io.haifa.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.credential.api.CredentialBroker;
import io.haifa.agent.credential.api.CredentialRequirement;
import io.haifa.agent.credential.api.SecretRedactor;
import io.haifa.agent.mcp.client.McpClientFacade;
import io.haifa.agent.mcp.client.McpConnectionManager;
import io.haifa.agent.mcp.client.McpConnectionState;
import io.haifa.agent.mcp.client.McpServerSnapshot;
import io.haifa.agent.mcp.config.McpCredentialInjection;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.protocol.McpListToolsPage;
import io.haifa.agent.mcp.protocol.McpRemoteTool;
import io.haifa.agent.mcp.protocol.McpRemoteToolResult;
import io.haifa.agent.mcp.tool.InMemoryMcpToolBindingStore;
import io.haifa.agent.mcp.tool.McpDiscoveryContext;
import io.haifa.agent.mcp.tool.McpToolDefinitionMapper;
import io.haifa.agent.mcp.tool.McpToolDiscoveryService;
import io.haifa.agent.mcp.transport.http.McpHttpCredentialContext;
import io.haifa.agent.tool.api.ToolDefinitionHash;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpCredentialTest {
    @Test
    void injectsHttpCredentialOnlyAtApprovedRequestOrigin() {
        CredentialRequirement requirement = requirement();
        McpCredentialInjection injection = new McpCredentialInjection(requirement, "Authorization", "Bearer ");
        var context = new McpHttpCredentialContext(List.of(injection), "https://utility.example:443");
        Map<String, String> credentials = Map.of("utility-token", "secret-token");

        HttpRequest request = context.withCredentials(credentials, () -> {
            var builder = HttpRequest.newBuilder(URI.create("https://utility.example/mcp"));
            context.customize(builder, "POST", URI.create("https://utility.example/mcp"), "{}", context.snapshot());
            return builder.build();
        });

        assertThat(request.headers().firstValue("Authorization")).contains("Bearer secret-token");
        assertThat(request.toString()).doesNotContain("secret-token");
        assertThatThrownBy(() -> context.withCredentials(credentials, () -> {
                    context.customize(
                            HttpRequest.newBuilder(),
                            "POST",
                            URI.create("https://other.example/mcp"),
                            "{}",
                            context.snapshot());
                    return null;
                }))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void authenticatedDiscoveryResolvesConfiguredCredentials() {
        var base = McpTestFixtures.httpServer(URI.create("http://127.0.0.1:8091/mcp"), Set.of("time_now"));
        var injection = new McpCredentialInjection(requirement(), "Authorization", "Bearer ");
        var server = McpServerDefinition.create(
                base.serverId(),
                base.displayName(),
                base.enabled(),
                base.protocol(),
                base.transport(),
                base.importPolicy(),
                base.connectionPolicy(),
                List.of(injection),
                base.bindingVersion());
        var client = new DiscoveryClient(server);
        var manager = new McpConnectionManager(List.of(server), (definition, identity) -> client);
        var broker = new CredentialBroker() {
            @Override
            public java.util.Optional<String> getSecret(String credentialId) {
                return java.util.Optional.ofNullable(Map.of("utility-token", "secret").get(credentialId));
            }

            @Override
            public SecretRedactor redactor() {
                return SecretRedactor.noop();
            }
        };
        var mapper = new McpToolDefinitionMapper(
                ignored -> new ToolDefinitionHash("c".repeat(64)), new InMemoryMcpToolBindingStore());
        var discovery = new McpToolDiscoveryService(
                manager,
                mapper,
                broker,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                4,
                20,
                1024 * 1024,
                Duration.ofSeconds(10));

        var candidates = discovery.discover(
                server.serverId(),
                new McpDiscoveryContext(McpTestFixtures.TENANT, McpTestFixtures.PRINCIPAL));

        assertThat(candidates).hasSize(1);
        assertThat(client.initializedCredentials).containsEntry("utility-token", "secret");
        assertThat(client.listToolsCredentials).containsEntry("utility-token", "secret");
    }

    private static CredentialRequirement requirement() {
        return new CredentialRequirement("utility-token");
    }

    private static final class DiscoveryClient implements McpClientFacade {
        private final McpServerDefinition server;
        private McpConnectionState state = McpConnectionState.DISCONNECTED;
        private Map<String, String> initializedCredentials;
        private Map<String, String> listToolsCredentials;

        private DiscoveryClient(McpServerDefinition server) {
            this.server = server;
        }

        @Override
        public McpServerSnapshot initialize(Map<String, String> credentials) {
            this.initializedCredentials = credentials;
            state = McpConnectionState.READY;
            return new McpServerSnapshot(
                    server.serverId(),
                    server.bindingReference(),
                    server.bindingDigest(),
                    McpProtocolProfile.VERSION_2025_11_25,
                    McpProtocolProfile.VERSION_2025_11_25,
                    "stub",
                    "1",
                    true,
                    false,
                    false,
                    false);
        }

        @Override
        public McpListToolsPage listTools(String cursor, Map<String, String> credentials) {
            this.listToolsCredentials = credentials;
            return new McpListToolsPage(
                    List.of(new McpRemoteTool(
                            "time_now",
                            "Time",
                            "Time",
                            Map.of("type", "object"),
                            Map.of("type", "object", "additionalProperties", true),
                            Map.of(),
                            Map.of())),
                    Optional.empty());
        }

        @Override
        public McpRemoteToolResult callTool(
                String name,
                Map<String, Object> arguments,
                Map<String, String> credentials,
                io.haifa.agent.tool.api.ToolInvocationObserver observer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public McpConnectionState state() {
            return state;
        }

        @Override
        public void close() {
            state = McpConnectionState.DISCONNECTED;
        }
    }
}
