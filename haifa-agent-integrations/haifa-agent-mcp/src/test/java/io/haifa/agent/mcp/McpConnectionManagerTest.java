package io.haifa.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.mcp.client.McpClientFacade;
import io.haifa.agent.mcp.client.McpConnection;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        Map<String, String> credential = Map.of("key-a", "top-secret");

        var first = manager.acquire(server.serverId(), McpTestFixtures.TENANT, McpTestFixtures.PRINCIPAL, credential);
        var reused = manager.acquire(server.serverId(), McpTestFixtures.TENANT, McpTestFixtures.PRINCIPAL, credential);
        var otherPrincipal =
                manager.acquire(server.serverId(), McpTestFixtures.TENANT, new PrincipalRef("bob", "user"), credential);

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
                public McpServerSnapshot initialize(Map<String, String> credentials) {
                    if (attempt == 1) {
                        throw new ToolInvocationException(
                                "MCP_INITIALIZE_FAILED", ToolDispatchState.OUTCOME_UNKNOWN, "temporary failure");
                    }
                    return super.initialize(credentials);
                }
            };
        });

        var connection =
                manager.acquire(server.serverId(), McpTestFixtures.TENANT, McpTestFixtures.PRINCIPAL, Map.of());

        assertThat(connection.client().state()).isEqualTo(McpConnectionState.READY);
        assertThat(created).hasValue(2);
        manager.close();
    }

    @Test
    void concurrentAcquiresForOneBindingShareASingleInitialization() throws Exception {
        var server = McpTestFixtures.httpServer(URI.create("http://127.0.0.1:8091/mcp"), Set.of());
        AtomicInteger created = new AtomicInteger();
        CountDownLatch initializationStarted = new CountDownLatch(1);
        CountDownLatch releaseInitialization = new CountDownLatch(1);
        var manager = new McpConnectionManager(List.of(server), (definition, identity) -> {
            created.incrementAndGet();
            return new FakeClient(definition.serverId().value(), definition.bindingReference()) {
                @Override
                public McpServerSnapshot initialize(Map<String, String> credentials) {
                    initializationStarted.countDown();
                    await(releaseInitialization);
                    return super.initialize(credentials);
                }
            };
        });

        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            try {
                List<Future<McpConnection>> acquires = new ArrayList<>();
                for (int index = 0; index < 4; index++) {
                    acquires.add(executor.submit(() -> manager.acquire(
                            server.serverId(), McpTestFixtures.TENANT, McpTestFixtures.PRINCIPAL, Map.of())));
                }
                assertThat(initializationStarted.await(10, TimeUnit.SECONDS)).isTrue();
                releaseInitialization.countDown();

                Set<McpConnection> connections = new HashSet<>();
                for (Future<McpConnection> acquire : acquires) {
                    connections.add(acquire.get(10, TimeUnit.SECONDS));
                }
                assertThat(connections).hasSize(1);
                assertThat(created).hasValue(1);
            } finally {
                releaseInitialization.countDown();
            }
        }
        manager.close();
    }

    @Test
    void acquiresForDifferentBindingsDoNotSerializeBehindABlockingHandshake() throws Exception {
        var server = McpTestFixtures.httpServer(URI.create("http://127.0.0.1:8091/mcp"), Set.of());
        CountDownLatch firstInitializationStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstInitialization = new CountDownLatch(1);
        var manager = new McpConnectionManager(
                List.of(server),
                (definition, identity) -> new FakeClient(definition.serverId().value(), definition.bindingReference()) {
                    @Override
                    public McpServerSnapshot initialize(Map<String, String> credentials) {
                        if ("alice".equals(identity.principal().principalId())) {
                            firstInitializationStarted.countDown();
                            await(releaseFirstInitialization);
                        }
                        return super.initialize(credentials);
                    }
                });

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<McpConnection> first = executor.submit(() ->
                    manager.acquire(server.serverId(), McpTestFixtures.TENANT, McpTestFixtures.PRINCIPAL, Map.of()));
            try {
                assertThat(firstInitializationStarted.await(10, TimeUnit.SECONDS))
                        .isTrue();
                Future<McpConnection> other = executor.submit(() -> manager.acquire(
                        server.serverId(), McpTestFixtures.TENANT, new PrincipalRef("bob", "user"), Map.of()));

                assertThat(other.get(10, TimeUnit.SECONDS).client().state()).isEqualTo(McpConnectionState.READY);
                assertThat(first.isDone()).isFalse();
            } finally {
                releaseFirstInitialization.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS).client().state()).isEqualTo(McpConnectionState.READY);
        }
        manager.close();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", exception);
        }
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
        public McpServerSnapshot initialize(Map<String, String> credentials) {
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
        public McpListToolsPage listTools(String cursor, Map<String, String> credentials) {
            return new McpListToolsPage(new ArrayList<>(), Optional.empty());
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
