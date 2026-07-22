package io.haifa.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.mcp.client.McpClientFacade;
import io.haifa.agent.mcp.client.McpConnectionManager;
import io.haifa.agent.mcp.client.McpConnectionState;
import io.haifa.agent.mcp.client.McpServerSnapshot;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.protocol.McpListToolsPage;
import io.haifa.agent.mcp.protocol.McpRemoteToolResult;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class McpConnectionManagerTest {
    @Test
    void partitionsConnectionsByTenantPrincipalAndCredentialReferenceWithoutSecrets() {
        var server = McpTestFixtures.httpServer(URI.create("http://127.0.0.1:8091/mcp"), java.util.Set.of());
        AtomicInteger created = new AtomicInteger();
        var manager = new McpConnectionManager(List.of(server), (definition, identity) -> {
            created.incrementAndGet();
            return new FakeClient(definition.serverId().value(), definition.bindingReference());
        });
        CredentialLease credential = McpTestFixtures.lease("binding-a", "top-secret");

        var first = manager.acquire(
                server.serverId(), McpTestFixtures.TENANT, McpTestFixtures.PRINCIPAL, List.of(credential));
        var reused = manager.acquire(
                server.serverId(), McpTestFixtures.TENANT, McpTestFixtures.PRINCIPAL, List.of(credential));
        var otherPrincipal = manager.acquire(
                server.serverId(), McpTestFixtures.TENANT, new PrincipalRef("bob", "user"), List.of(credential));

        assertThat(reused).isSameAs(first);
        assertThat(otherPrincipal).isNotSameAs(first);
        assertThat(created).hasValue(2);
        assertThat(first.toString()).doesNotContain("top-secret");
        manager.close();
    }

    @Test
    void retriesOnlyInitializationFailuresWithUnknownOutcomeWithinConfiguredBound() {
        var server = McpTestFixtures.httpServer(URI.create("http://127.0.0.1:8091/mcp"), java.util.Set.of());
        AtomicInteger created = new AtomicInteger();
        var manager = new McpConnectionManager(List.of(server), (definition, identity) -> {
            int attempt = created.incrementAndGet();
            return new FakeClient(definition.serverId().value(), definition.bindingReference()) {
                @Override
                public McpServerSnapshot initialize(List<CredentialLease> credentials) {
                    if (attempt == 1) {
                        throw new ToolInvocationException(
                                "MCP_INITIALIZE_FAILED", ToolDispatchState.OUTCOME_UNKNOWN, "temporary failure");
                    }
                    return super.initialize(credentials);
                }
            };
        });

        var connection =
                manager.acquire(server.serverId(), McpTestFixtures.TENANT, McpTestFixtures.PRINCIPAL, List.of());

        assertThat(connection.client().state()).isEqualTo(McpConnectionState.READY);
        assertThat(created).hasValue(2);
        manager.close();
    }

    private static class FakeClient implements McpClientFacade {
        private final String serverId;
        private final String binding;
        private McpConnectionState state = McpConnectionState.DISCONNECTED;

        private FakeClient(String serverId, String binding) {
            this.serverId = serverId;
            this.binding = binding;
        }

        @Override
        public McpServerSnapshot initialize(List<CredentialLease> credentials) {
            state = McpConnectionState.READY;
            return new McpServerSnapshot(
                    new io.haifa.agent.mcp.config.McpServerId(serverId),
                    binding,
                    "digest",
                    McpProtocolProfile.VERSION_2025_11_25,
                    McpProtocolProfile.VERSION_2025_11_25,
                    "stub",
                    "1",
                    true,
                    true,
                    false,
                    false);
        }

        @Override
        public McpListToolsPage listTools(String cursor, List<CredentialLease> credentials) {
            return new McpListToolsPage(new ArrayList<>(), Optional.empty());
        }

        @Override
        public McpRemoteToolResult callTool(
                String name,
                Map<String, Object> arguments,
                List<CredentialLease> credentials,
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
