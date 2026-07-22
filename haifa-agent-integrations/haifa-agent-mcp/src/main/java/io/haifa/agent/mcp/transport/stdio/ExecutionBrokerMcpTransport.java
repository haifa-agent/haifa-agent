package io.haifa.agent.mcp.transport.stdio;

import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ManagedProcessSession;
import io.haifa.agent.execution.api.ProcessInputChunk;
import io.haifa.agent.mcp.client.McpConnectionIdentity;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.config.StdioDefinition;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class ExecutionBrokerMcpTransport implements McpClientTransport {
    private final McpServerDefinition server;
    private final StdioDefinition stdio;
    private final McpConnectionIdentity identity;
    private final ExecutionBroker executionBroker;
    private final McpManagedProcessLaunchFactory launches;
    private final McpStdioCredentialContext credentials;
    private final McpJsonMapper mapper;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private volatile Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;
    private volatile Consumer<Throwable> exceptionHandler = ignored -> {};
    private volatile ManagedProcessSession session;
    private volatile McpManagedProcessLaunch launch;

    public ExecutionBrokerMcpTransport(
            McpServerDefinition server,
            McpConnectionIdentity identity,
            ExecutionBroker executionBroker,
            McpManagedProcessLaunchFactory launches,
            McpStdioCredentialContext credentials,
            McpJsonMapper mapper) {
        this.server = Objects.requireNonNull(server, "server");
        this.stdio = (StdioDefinition) server.transport();
        this.identity = Objects.requireNonNull(identity, "identity");
        this.executionBroker = Objects.requireNonNull(executionBroker, "executionBroker");
        this.launches = Objects.requireNonNull(launches, "launches");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> messageHandler) {
        return Mono.fromRunnable(() -> {
            if (handler != null) throw new IllegalStateException("MCP stdio transport is already connected");
            handler = Objects.requireNonNull(messageHandler, "messageHandler");
        });
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.deferContextual(contextView -> Mono.fromRunnable(() -> {
                    if (closing.get()) throw new IllegalStateException("MCP stdio transport is closing");
                    McpTransportContext transportContext =
                            contextView.getOrDefault(McpTransportContext.KEY, McpTransportContext.EMPTY);
                    McpStdioCredentialContext.RequestScope requestScope = credentials.requestScope(transportContext);
                    ManagedProcessSession current = ensureSession(requestScope.credentials());
                    try {
                        byte[] json = mapper.writeValueAsBytes(message);
                        if (json.length > stdio.maxFrameBytes()) {
                            throw new IllegalArgumentException("MCP stdio outbound frame exceeds its budget");
                        }
                        byte[] framed = java.util.Arrays.copyOf(json, json.length + 1);
                        framed[json.length] = '\n';
                        requestScope.dispatched();
                        current.write(new ProcessInputChunk(framed));
                    } catch (java.io.IOException exception) {
                        throw new IllegalStateException("failed to encode MCP stdio frame", exception);
                    }
                }))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> exceptionHandler) {
        this.exceptionHandler = Objects.requireNonNull(exceptionHandler, "exceptionHandler");
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(this::closeNow)
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public <T> T unmarshalFrom(Object value, TypeRef<T> type) {
        return mapper.convertValue(value, type);
    }

    @Override
    public List<String> protocolVersions() {
        return List.of(McpProtocolProfile.VERSION_2025_11_25);
    }

    private ManagedProcessSession ensureSession(List<io.haifa.agent.credential.api.CredentialLease> leases) {
        ManagedProcessSession current = session;
        if (current != null && !current.isClosed()) return current;
        synchronized (lifecycleLock) {
            current = session;
            if (current != null && !current.isClosed()) return current;
            McpManagedProcessLaunch prepared = launches.prepare(server, identity, leases);
            try {
                current = executionBroker.openManagedSession(prepared.request());
                ManagedProcessSession opened = current;
                launch = prepared;
                session = opened;
                Thread.ofVirtual()
                        .name("haifa-mcp-stdio-" + server.serverId().value())
                        .start(() -> readLoop(opened));
                return opened;
            } catch (RuntimeException exception) {
                prepared.close();
                throw exception;
            }
        }
    }

    private void readLoop(ManagedProcessSession process) {
        var stdout = new ByteArrayOutputStream();
        int stderrBytes = 0;
        try {
            while (!closing.get()) {
                var chunk = process.read(Duration.ofMillis(100));
                if (chunk.isPresent()) {
                    var value = chunk.orElseThrow();
                    if (value.channel() == ExecutionOutputChannel.STDERR) {
                        stderrBytes = Math.addExact(stderrBytes, value.bytes().length);
                        if (stderrBytes > stdio.maxStderrBytes()) {
                            throw new IllegalStateException("MCP stdio stderr exceeded its budget");
                        }
                    } else {
                        appendFrames(stdout, value.bytes());
                    }
                } else if (process.exit().isDone()) {
                    if (stdout.size() != 0) throw new IllegalStateException("unterminated MCP stdio frame");
                    break;
                }
            }
        } catch (RuntimeException exception) {
            if (!closing.get()) {
                process.cancel();
                exceptionHandler.accept(exception);
            }
        }
    }

    private void appendFrames(ByteArrayOutputStream pending, byte[] bytes) {
        for (byte value : bytes) {
            if (value == '\n') {
                byte[] frame = pending.toByteArray();
                pending.reset();
                if (frame.length > 0 && frame[frame.length - 1] == '\r') {
                    frame = java.util.Arrays.copyOf(frame, frame.length - 1);
                }
                if (frame.length == 0) continue;
                dispatch(frame);
            } else {
                if (pending.size() >= stdio.maxFrameBytes()) {
                    throw new IllegalStateException("MCP stdio inbound frame exceeds its budget");
                }
                pending.write(value);
            }
        }
    }

    private void dispatch(byte[] frame) {
        try {
            String json = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(frame))
                    .toString();
            McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(mapper, json);
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> current = handler;
            if (current == null) throw new IllegalStateException("MCP stdio transport is not connected");
            current.apply(Mono.just(message))
                    .subscribe(
                            response -> {
                                if (response != null) sendMessage(response).subscribe();
                            },
                            exceptionHandler);
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new IllegalStateException("MCP stdio frame is not valid UTF-8", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("MCP stdio frame is not valid JSON-RPC", exception);
        }
    }

    private void closeNow() {
        if (!closing.compareAndSet(false, true)) return;
        synchronized (lifecycleLock) {
            try {
                if (session != null) session.close();
            } finally {
                try {
                    if (launch != null) launch.close();
                } finally {
                    session = null;
                    launch = null;
                }
            }
        }
    }
}
