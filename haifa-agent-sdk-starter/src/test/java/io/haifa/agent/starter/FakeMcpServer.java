package io.haifa.agent.starter;

import io.haifa.agent.mcp.client.McpClientFacade;
import io.haifa.agent.mcp.client.McpClientFactory;
import io.haifa.agent.mcp.client.McpConnectionIdentity;
import io.haifa.agent.mcp.client.McpConnectionState;
import io.haifa.agent.mcp.client.McpServerSnapshot;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.protocol.McpListToolsPage;
import io.haifa.agent.mcp.protocol.McpRemoteContent;
import io.haifa.agent.mcp.protocol.McpRemoteTool;
import io.haifa.agent.mcp.protocol.McpRemoteToolResult;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.haifa.agent.tool.api.ToolSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process MCP server double used to exercise the native MCP Client DX without opening a socket.
 *
 * <p>It implements the same {@link McpClientFactory} seam the Starter uses for real Streamable HTTP
 * connections, so Tool discovery, mapping, binding and invocation run through the production MCP
 * Integration exactly as they do against a remote server.
 */
public final class FakeMcpServer implements McpClientFactory {
    private final Map<String, List<String>> toolsByServer = new LinkedHashMap<>();
    private final Set<String> failingServers = new LinkedHashSet<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> authorizationValues = new CopyOnWriteArrayList<>();
    private final AtomicInteger openClients = new AtomicInteger();
    private final AtomicInteger closedClients = new AtomicInteger();

    public FakeMcpServer serving(String serverName, String... remoteToolNames) {
        toolsByServer.put(serverName, List.of(remoteToolNames));
        return this;
    }

    public FakeMcpServer failing(String serverName) {
        failingServers.add(serverName);
        return this;
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    public List<String> authorizationValues() {
        return List.copyOf(authorizationValues);
    }

    public int openClients() {
        return openClients.get();
    }

    public int closedClients() {
        return closedClients.get();
    }

    @Override
    public McpClientFacade create(McpServerDefinition server, McpConnectionIdentity identity) {
        openClients.incrementAndGet();
        return new FakeClient(server);
    }

    private final class FakeClient implements McpClientFacade {
        private final McpServerDefinition server;
        private McpConnectionState state = McpConnectionState.DISCONNECTED;

        private FakeClient(McpServerDefinition server) {
            this.server = server;
        }

        @Override
        public McpServerSnapshot initialize(Map<String, String> credentials) {
            String name = server.serverId().value();
            if (failingServers.contains(name)) {
                throw new ToolInvocationException(
                        "MCP_CONNECT_FAILED", ToolDispatchState.NOT_DISPATCHED, "fake MCP server refused to connect");
            }
            recordCredentials(credentials);
            state = McpConnectionState.READY;
            return new McpServerSnapshot(
                    server.serverId(),
                    server.bindingReference(),
                    server.bindingDigest(),
                    server.protocol().targetVersion(),
                    server.protocol().targetVersion(),
                    "fake-mcp-server",
                    "1.0.0",
                    true,
                    false,
                    false,
                    false);
        }

        @Override
        public McpListToolsPage listTools(String cursor, Map<String, String> credentials) {
            recordCredentials(credentials);
            List<McpRemoteTool> tools = new ArrayList<>();
            for (String name : toolsByServer.getOrDefault(server.serverId().value(), List.of())) {
                tools.add(remoteTool(name));
            }
            return new McpListToolsPage(tools, Optional.empty());
        }

        @Override
        public McpRemoteToolResult callTool(
                String name,
                Map<String, Object> arguments,
                Map<String, String> credentials,
                ToolInvocationObserver observer) {
            recordCredentials(credentials);
            calls.add(server.serverId().value() + ":" + name + ":" + arguments.get("query"));
            observer.dispatched();
            observer.acknowledged();
            return new McpRemoteToolResult(
                    false,
                    List.of(new McpRemoteContent(
                            McpRemoteContent.Kind.TEXT, name + " answered " + arguments.get("query"), "text/plain", 0)),
                    Map.of("hits", List.of(name + "-hit")));
        }

        @Override
        public McpConnectionState state() {
            return state;
        }

        @Override
        public void close() {
            if (state != McpConnectionState.DISCONNECTED) closedClients.incrementAndGet();
            state = McpConnectionState.DISCONNECTED;
        }

        private void recordCredentials(Map<String, String> credentials) {
            server.discoveryCredentials().forEach(injection -> {
                String secret = credentials.get(injection.requirement().credentialId());
                if (secret == null || secret.isBlank()) {
                    throw new ToolInvocationException(
                            "MCP_CREDENTIAL_MISSING",
                            ToolDispatchState.NOT_DISPATCHED,
                            "fake MCP server did not receive its credential");
                }
                authorizationValues.add(injection.targetName() + ": " + injection.valuePrefix() + secret);
            });
        }
    }

    private static McpRemoteTool remoteTool(String name) {
        return new McpRemoteTool(
                name,
                name,
                "Remote " + name,
                Map.of(
                        "$schema",
                        ToolSchema.DRAFT_2020_12,
                        "type",
                        "object",
                        "properties",
                        Map.of("query", Map.of("type", "string")),
                        "required",
                        List.of("query"),
                        "additionalProperties",
                        false),
                Map.of(),
                Map.of(),
                Map.of());
    }
}
