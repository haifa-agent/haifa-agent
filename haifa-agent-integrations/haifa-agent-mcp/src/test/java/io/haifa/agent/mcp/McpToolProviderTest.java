package io.haifa.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.mcp.client.McpClientFacade;
import io.haifa.agent.mcp.client.McpConnectionManager;
import io.haifa.agent.mcp.client.McpConnectionState;
import io.haifa.agent.mcp.client.McpServerSnapshot;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.protocol.McpListToolsPage;
import io.haifa.agent.mcp.protocol.McpRemoteTool;
import io.haifa.agent.mcp.protocol.McpRemoteToolResult;
import io.haifa.agent.mcp.tool.InMemoryMcpToolBindingStore;
import io.haifa.agent.mcp.tool.McpContentMapper;
import io.haifa.agent.mcp.tool.McpToolDefinitionMapper;
import io.haifa.agent.mcp.tool.McpToolProvider;
import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.api.ToolDefinitionHash;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class McpToolProviderTest {
    @Test
    void mapsCancellationBeforeDispatchToStableMcpFailure() {
        var fixture = fixture(null);
        var request = request(fixture.binding(), () -> true, Instant.now().plusSeconds(5));

        assertThatThrownBy(() -> fixture.provider().invoke(request))
                .isInstanceOfSatisfying(ToolInvocationException.class, failure -> {
                    assertThat(failure.failureCode()).isEqualTo("MCP_CALL_CANCELLED");
                    assertThat(failure.dispatchState()).isEqualTo(ToolDispatchState.NOT_DISPATCHED);
                });
    }

    @Test
    void cancelsBlockingCallAndMarksOutcomeUnknownAfterDispatch() throws Exception {
        var client = new BlockingClient();
        var fixture = fixture(client);
        var cancelled = new AtomicBoolean();
        var request = request(fixture.binding(), cancelled::get, Instant.now().plusSeconds(5));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var invocation = executor.submit(() -> fixture.provider().invoke(request));
            assertThat(client.dispatched.await(2, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);

            assertThatThrownBy(invocation::get)
                    .cause()
                    .isInstanceOfSatisfying(ToolInvocationException.class, failure -> {
                        assertThat(failure.failureCode()).isEqualTo("MCP_CALL_CANCELLED");
                        assertThat(failure.dispatchState()).isEqualTo(ToolDispatchState.OUTCOME_UNKNOWN);
                    });
            assertThat(client.closed).isTrue();
        }
    }

    private static Fixture fixture(BlockingClient suppliedClient) {
        var server = McpTestFixtures.httpServer(URI.create("http://127.0.0.1:8091/mcp"), Set.of("time_now"));
        var store = new InMemoryMcpToolBindingStore();
        var mapper = new McpToolDefinitionMapper(ignored -> new ToolDefinitionHash("d".repeat(64)), store);
        var candidate = mapper.map(
                server,
                McpProtocolProfile.VERSION_2025_11_25,
                new McpRemoteTool(
                        "time_now",
                        "Time now",
                        "Returns the current time",
                        Map.of("type", "object"),
                        Map.of("type", "object"),
                        Map.of(),
                        Map.of()));
        var definition = candidate.definition().orElseThrow();
        var snapshot = candidate.binding().orElseThrow();
        var coordinate = new ToolCoordinate(
                definition.name(), definition.version(), definition.providerId(), snapshot.localDefinitionHash());
        var binding = new FrozenToolBinding(
                candidate.alias().orElseThrow(), coordinate, definition, snapshot.bindingReference(), "catalog-v1");
        McpClientFacade client = suppliedClient == null ? new BlockingClient() : suppliedClient;
        var connections = new McpConnectionManager(List.of(server), (ignored, identity) -> client);
        var provider = new McpToolProvider(server.serverId(), store, connections, new McpContentMapper(value -> value));
        return new Fixture(provider, binding);
    }

    private static ToolInvocationRequest request(
            FrozenToolBinding binding, io.haifa.agent.tool.api.ToolCancellation cancellation, Instant deadline) {
        return new ToolInvocationRequest(
                binding,
                new ToolCallId("call-1"),
                new AgentRunId("run-1"),
                McpTestFixtures.TENANT,
                McpTestFixtures.PRINCIPAL,
                new ToolArguments(
                        binding.definition().inputSchema().id(),
                        binding.definition().inputSchema().version(),
                        Map.of()),
                deadline,
                Optional.empty(),
                cancellation,
                List.of());
    }

    private record Fixture(McpToolProvider provider, FrozenToolBinding binding) {}

    private static final class BlockingClient implements McpClientFacade {
        private final CountDownLatch dispatched = new CountDownLatch(1);
        private volatile boolean closed;

        @Override
        public McpServerSnapshot initialize(List<CredentialLease> credentials) {
            return new McpServerSnapshot(
                    new io.haifa.agent.mcp.config.McpServerId("utility"),
                    "mcp-server:binding",
                    "digest",
                    McpProtocolProfile.VERSION_2025_11_25,
                    McpProtocolProfile.VERSION_2025_11_25,
                    "stub",
                    "1.0.0",
                    true,
                    false,
                    false,
                    false);
        }

        @Override
        public McpListToolsPage listTools(String cursor, List<CredentialLease> credentials) {
            return new McpListToolsPage(List.of(), Optional.empty());
        }

        @Override
        public McpRemoteToolResult callTool(
                String name,
                Map<String, Object> arguments,
                List<CredentialLease> credentials,
                ToolInvocationObserver observer) {
            observer.dispatched();
            dispatched.countDown();
            while (!closed) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", exception);
                }
            }
            throw new IllegalStateException("closed");
        }

        @Override
        public McpConnectionState state() {
            return closed ? McpConnectionState.DISCONNECTED : McpConnectionState.READY;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
