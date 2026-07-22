package io.haifa.agent.mcp.tool;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.mcp.client.McpConnection;
import io.haifa.agent.mcp.client.McpConnectionManager;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.config.McpServerId;
import io.haifa.agent.mcp.config.StdioDefinition;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class McpToolProvider implements ToolProvider {
    private final McpServerId serverId;
    private final ToolProviderId providerId;
    private final McpToolBindingStore bindings;
    private final McpConnectionManager connections;
    private final McpContentMapper contentMapper;
    private final Clock clock;

    public McpToolProvider(
            McpServerId serverId,
            McpToolBindingStore bindings,
            McpConnectionManager connections,
            McpContentMapper contentMapper) {
        this(serverId, bindings, connections, contentMapper, Clock.systemUTC());
    }

    public McpToolProvider(
            McpServerId serverId,
            McpToolBindingStore bindings,
            McpConnectionManager connections,
            McpContentMapper contentMapper,
            Clock clock) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.providerId = new ToolProviderId("mcp." + serverId.value());
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.contentMapper = Objects.requireNonNull(contentMapper, "contentMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ToolProviderId id() {
        return providerId;
    }

    @Override
    public ToolResult invoke(ToolInvocationRequest request) {
        rejectCancellationBeforeDispatch(request);
        var binding = bindings.find(request.binding().providerBindingReference())
                .orElseThrow(() -> failure("MCP_BINDING_MISSING", "frozen MCP binding is unavailable"));
        var server = connections.definition(serverId);
        if (!binding.serverId().equals(serverId.value())
                || !binding.serverBindingVersion().equals(server.bindingVersion())
                || !binding.serverBindingDigest().equals(server.bindingDigest())
                || !binding.targetProtocolVersion().equals(McpProtocolProfile.VERSION_2025_11_25)
                || !binding.negotiatedProtocolVersion().equals(McpProtocolProfile.VERSION_2025_11_25)
                || !binding.transportIdentityReference()
                        .equals(server.transport().identityReference())
                || !binding.localDefinitionHash()
                        .equals(request.binding().coordinate().definitionHash())
                || !request.binding().definition().providerId().equals(providerId)) {
            throw failure("MCP_BINDING_DRIFT", "frozen MCP binding failed integrity validation");
        }
        McpConnection connection =
                connections.acquire(serverId, request.tenant(), request.principal(), request.credentialLeases());
        rejectCancellationBeforeDispatch(request);
        if (!clock.instant().isBefore(request.deadline())) {
            throw failure("MCP_CALL_DEADLINE_EXCEEDED", "MCP tool call deadline elapsed before dispatch");
        }
        try {
            AtomicBoolean dispatched = new AtomicBoolean();
            var guardedObserver = new io.haifa.agent.tool.api.ToolInvocationObserver() {
                @Override
                public void dispatched() {
                    dispatched.set(true);
                    request.observer().dispatched();
                }

                @Override
                public void acknowledged() {
                    request.observer().acknowledged();
                }
            };
            CompletableFuture<io.haifa.agent.mcp.protocol.McpRemoteToolResult> result = new CompletableFuture<>();
            Thread worker = Thread.ofVirtual().name("haifa-mcp-call").start(() -> {
                try {
                    result.complete(connection
                            .client()
                            .callTool(
                                    binding.remoteToolName(),
                                    request.arguments().values(),
                                    request.credentialLeases(),
                                    guardedObserver));
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                }
            });
            var remoteResult = await(request, connection, result, worker, dispatched);
            request.observer().acknowledged();
            return contentMapper.map(remoteResult);
        } catch (ToolInvocationException exception) {
            if ("MCP_SESSION_INVALID".equals(exception.failureCode())) connections.invalidate(connection);
            throw exception;
        } catch (RuntimeException exception) {
            throw new ToolInvocationException(
                    "MCP_CALL_OUTCOME_UNKNOWN",
                    ToolDispatchState.OUTCOME_UNKNOWN,
                    "MCP tool call outcome is unknown",
                    exception);
        } finally {
            if (server.transport() instanceof StdioDefinition) connections.invalidate(connection);
        }
    }

    private static ToolInvocationException failure(String code, String message) {
        return new ToolInvocationException(code, ToolDispatchState.NOT_DISPATCHED, message);
    }

    private static void rejectCancellationBeforeDispatch(ToolInvocationRequest request) {
        if (request.cancellation().isCancellationRequested()) {
            throw failure("MCP_CALL_CANCELLED", "MCP tool call was cancelled before dispatch");
        }
    }

    private io.haifa.agent.mcp.protocol.McpRemoteToolResult await(
            ToolInvocationRequest request,
            McpConnection connection,
            CompletableFuture<io.haifa.agent.mcp.protocol.McpRemoteToolResult> result,
            Thread worker,
            AtomicBoolean dispatched) {
        while (true) {
            if (result.isDone()) return completed(result);
            Instant now = clock.instant();
            boolean cancelled = request.cancellation().isCancellationRequested();
            boolean deadlineExceeded = !now.isBefore(request.deadline());
            if (cancelled || deadlineExceeded) {
                worker.interrupt();
                connections.invalidate(connection);
                throw new ToolInvocationException(
                        cancelled ? "MCP_CALL_CANCELLED" : "MCP_CALL_DEADLINE_EXCEEDED",
                        dispatched.get() ? ToolDispatchState.OUTCOME_UNKNOWN : ToolDispatchState.NOT_DISPATCHED,
                        cancelled ? "MCP tool call was cancelled" : "MCP tool call deadline elapsed");
            }
            long remainingMillis =
                    Math.max(1L, Duration.between(now, request.deadline()).toMillis());
            try {
                return result.get(Math.min(25L, remainingMillis), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // Re-check cancellation and deadline without busy-waiting.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                worker.interrupt();
                connections.invalidate(connection);
                throw new ToolInvocationException(
                        "MCP_CALL_CANCELLED",
                        dispatched.get() ? ToolDispatchState.OUTCOME_UNKNOWN : ToolDispatchState.NOT_DISPATCHED,
                        "MCP tool call wait was interrupted",
                        exception);
            } catch (ExecutionException exception) {
                throw propagate(exception.getCause());
            }
        }
    }

    private static io.haifa.agent.mcp.protocol.McpRemoteToolResult completed(
            CompletableFuture<io.haifa.agent.mcp.protocol.McpRemoteToolResult> result) {
        try {
            return result.join();
        } catch (CompletionException exception) {
            throw propagate(exception.getCause());
        }
    }

    private static RuntimeException propagate(Throwable error) {
        if (error instanceof RuntimeException runtime) return runtime;
        if (error instanceof Error fatal) throw fatal;
        return new IllegalStateException("MCP call worker failed", error);
    }
}
