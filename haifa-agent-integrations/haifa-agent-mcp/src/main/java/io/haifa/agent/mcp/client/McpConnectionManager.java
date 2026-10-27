package io.haifa.agent.mcp.client;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.config.McpServerId;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;

public final class McpConnectionManager implements AutoCloseable {
    private final Map<McpServerId, McpServerDefinition> servers;
    private final McpClientFactory factory;
    private final ConcurrentHashMap<PoolKey, McpConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PoolKey, CompletableFuture<McpConnection>> pending = new ConcurrentHashMap<>();

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
            McpServerId serverId, TenantRef tenant, PrincipalRef principal, Map<String, String> credentials) {
        McpServerDefinition server = definition(serverId);
        PoolKey key = poolKey(server, tenant, principal, credentials);
        McpConnection existing = connections.get(key);
        if (ready(existing)) return existing;

        CompletableFuture<McpConnection> initialization = new CompletableFuture<>();
        CompletableFuture<McpConnection> shared = pending.putIfAbsent(key, initialization);
        if (shared != null) return await(shared);

        try {
            existing = connections.get(key);
            if (ready(existing)) {
                initialization.complete(existing);
                return existing;
            }
            McpConnection connection = connect(server, tenant, principal, credentials, existing);
            connections.put(key, connection);
            initialization.complete(connection);
            return connection;
        } catch (RuntimeException | Error failure) {
            initialization.completeExceptionally(failure);
            throw failure;
        } finally {
            pending.remove(key, initialization);
        }
    }

    private McpConnection connect(
            McpServerDefinition server,
            TenantRef tenant,
            PrincipalRef principal,
            Map<String, String> credentials,
            McpConnection stale) {
        // Initialization runs without any manager monitor: a blocking handshake must not pin the caller's
        // virtual thread, and bindings that differ may connect in parallel.
        if (stale != null) stale.client().close();
        int attempts = server.connectionPolicy().maxReconnectAttempts() + 1;
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            McpClientFacade client = factory.create(server, new McpConnectionIdentity(tenant, principal));
            try {
                return new McpConnection(client, client.initialize(credentials));
            } catch (RuntimeException exception) {
                client.close();
                lastFailure = exception;
                if (!reconnectable(exception) || attempt + 1 == attempts) throw exception;
                jitter(attempt);
            }
        }
        throw Objects.requireNonNull(lastFailure, "lastFailure");
    }

    private static McpConnection await(CompletableFuture<McpConnection> initialization) {
        try {
            return initialization.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MCP connection initialization interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable failure = exception.getCause();
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("MCP connection initialization failed", failure);
        }
    }

    private static PoolKey poolKey(
            McpServerDefinition server, TenantRef tenant, PrincipalRef principal, Map<String, String> credentials) {
        return new PoolKey(
                server.bindingReference(),
                tenant.tenantId(),
                principal.principalType(),
                principal.principalId(),
                credentials.keySet().stream().sorted().toList());
    }

    private static boolean ready(McpConnection connection) {
        return connection != null && connection.client().state() == McpConnectionState.READY;
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
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MCP reconnect interrupted", exception);
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
