package io.haifa.agent.mcp.client;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.config.McpServerId;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

public final class McpConnectionManager implements AutoCloseable {
    private final Map<McpServerId, McpServerDefinition> servers;
    private final McpClientFactory factory;
    private final ConcurrentHashMap<PoolKey, McpConnection> connections = new ConcurrentHashMap<>();

    public McpConnectionManager(Collection<McpServerDefinition> servers, McpClientFactory factory) {
        var definitions = new java.util.LinkedHashMap<McpServerId, McpServerDefinition>();
        for (McpServerDefinition server : servers) {
            McpServerDefinition existing = definitions.putIfAbsent(server.serverId(), server);
            if (existing != null) throw new IllegalArgumentException("duplicate MCP server id");
        }
        this.servers = Map.copyOf(definitions);
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public McpConnection acquire(
            McpServerId serverId, TenantRef tenant, PrincipalRef principal, List<CredentialLease> credentials) {
        McpServerDefinition server = definition(serverId);
        PoolKey key = new PoolKey(
                server.bindingReference(),
                tenant.tenantId(),
                principal.principalType(),
                principal.principalId(),
                credentials.stream()
                        .map(lease -> lease.reference().value())
                        .sorted()
                        .toList());
        McpConnection existing = connections.get(key);
        if (existing != null && existing.client().state() == McpConnectionState.READY) return existing;
        synchronized (connections) {
            existing = connections.get(key);
            if (existing != null && existing.client().state() == McpConnectionState.READY) return existing;
            if (existing != null) existing.client().close();
            int attempts = server.connectionPolicy().maxReconnectAttempts() + 1;
            RuntimeException lastFailure = null;
            for (int attempt = 0; attempt < attempts; attempt++) {
                McpClientFacade client = factory.create(server, new McpConnectionIdentity(tenant, principal));
                try {
                    McpConnection connection = new McpConnection(client, client.initialize(credentials));
                    connections.put(key, connection);
                    return connection;
                } catch (RuntimeException exception) {
                    client.close();
                    lastFailure = exception;
                    if (!reconnectable(exception) || attempt + 1 == attempts) throw exception;
                    jitter(attempt);
                }
            }
            throw Objects.requireNonNull(lastFailure, "lastFailure");
        }
    }

    public McpServerDefinition definition(McpServerId serverId) {
        McpServerDefinition server = servers.get(serverId);
        if (server == null || !server.enabled()) throw new IllegalStateException("MCP server is unavailable");
        return server;
    }

    public void invalidate(McpConnection connection) {
        connections.entrySet().removeIf(entry -> {
            if (entry.getValue() != connection) return false;
            entry.getValue().client().close();
            return true;
        });
    }

    @Override
    public void close() {
        connections.values().forEach(connection -> connection.client().close());
        connections.clear();
    }

    private static boolean reconnectable(RuntimeException exception) {
        return !(exception instanceof ToolInvocationException invocation)
                || invocation.dispatchState() == ToolDispatchState.OUTCOME_UNKNOWN;
    }

    private static void jitter(int attempt) {
        long upperMillis = Math.min(250L, 25L << Math.min(attempt, 3));
        long millis = ThreadLocalRandom.current().nextLong(1L, upperMillis + 1L);
        LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis));
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MCP reconnect interrupted");
        }
    }

    private record PoolKey(
            String serverBindingReference,
            String tenant,
            String principalType,
            String principal,
            List<String> credentialBindingReferences) {
        private PoolKey {
            credentialBindingReferences = List.copyOf(credentialBindingReferences);
        }
    }
}
