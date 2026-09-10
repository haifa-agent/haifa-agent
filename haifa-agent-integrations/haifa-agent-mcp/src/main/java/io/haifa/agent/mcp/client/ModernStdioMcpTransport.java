package io.haifa.agent.mcp.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ManagedProcessSession;
import io.haifa.agent.execution.api.ProcessInputChunk;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.config.StdioDefinition;
import io.haifa.agent.mcp.transport.stdio.McpManagedProcessLaunch;
import io.haifa.agent.mcp.transport.stdio.McpManagedProcessLaunchFactory;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

final class ModernStdioMcpTransport implements ModernMcpTransport {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};
    private final McpServerDefinition server;
    private final McpConnectionIdentity identity;
    private final io.haifa.agent.execution.api.ExecutionBroker executionBroker;
    private final McpManagedProcessLaunchFactory launches;
    private final StdioDefinition definition;
    private final ObjectMapper mapper;
    private final AtomicLong requestIds = new AtomicLong();
    private final ByteArrayOutputStream pendingFrame = new ByteArrayOutputStream();
    private final ArrayDeque<Map<String, Object>> messages = new ArrayDeque<>();
    private ManagedProcessSession session;
    private McpManagedProcessLaunch launch;
    private List<String> credentialReferences;
    private int stderrBytes;
    private boolean closed;

    ModernStdioMcpTransport(
            McpServerDefinition server,
            McpConnectionIdentity identity,
            io.haifa.agent.execution.api.ExecutionBroker executionBroker,
            McpManagedProcessLaunchFactory launches,
            StdioDefinition definition,
            ObjectMapper mapper) {
        this.server = server;
        this.identity = identity;
        this.executionBroker = executionBroker;
        this.launches = launches;
        this.definition = definition;
        this.mapper = mapper;
    }

    @Override
    public synchronized Map<String, Object> request(
            String method,
            Map<String, Object> parameters,
            Map<String, String> envelopeHeaders,
            Map<String, String> credentials,
            ToolInvocationObserver observer) {
        ensureOpen(credentials);
        long id = requestIds.incrementAndGet();
        Map<String, Object> params = new LinkedHashMap<>(parameters);
        params.put(
                "_meta",
                Map.of(
                        "io.modelcontextprotocol/protocolVersion", McpProtocolProfile.VERSION_2026_07_28,
                        "io.modelcontextprotocol/clientInfo", Map.of("name", "haifa-agent", "version", "0.1.0"),
                        "io.modelcontextprotocol/clientCapabilities", Map.of()));
        try {
            byte[] frame = (mapper.writeValueAsString(
                                    Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params))
                            + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            if (frame.length - 1 > definition.maxFrameBytes()) {
                throw new ToolInvocationException(
                        "MCP_STDIO_FRAME_TOO_LARGE",
                        ToolDispatchState.NOT_DISPATCHED,
                        "MCP stdio outbound frame exceeds its budget");
            }
            observer.dispatched();
            session.write(new ProcessInputChunk(frame));
            return await(id);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to encode MCP stdio request", exception);
        }
    }

    private Map<String, Object> await(long id) {
        long deadline = System.nanoTime() + definition.requestTimeout().toNanos();
        while (true) {
            Map<String, Object> queued = matchingMessage(id);
            if (queued != null) return result(queued);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new ToolInvocationException(
                        "MCP_STDIO_TIMEOUT", ToolDispatchState.OUTCOME_UNKNOWN, "MCP stdio request timed out");
            }
            Duration wait =
                    Duration.ofNanos(Math.min(remaining, Duration.ofMillis(100).toNanos()));
            var chunk = session.read(wait);
            if (chunk.isPresent()) {
                var output = chunk.orElseThrow();
                if (output.truncated()) {
                    throw new IllegalStateException("MCP stdio output was truncated");
                }
                if (output.channel() == ExecutionOutputChannel.STDERR) {
                    stderrBytes = Math.addExact(stderrBytes, output.bytes().length);
                    if (stderrBytes > definition.maxStderrBytes()) {
                        throw new IllegalStateException("MCP stdio stderr exceeded its budget");
                    }
                } else {
                    appendFrames(output.bytes());
                }
            } else if (session.exit().isDone()) {
                throw new IllegalStateException("MCP stdio process exited before responding");
            }
        }
    }

    private Map<String, Object> matchingMessage(long id) {
        var iterator = messages.iterator();
        while (iterator.hasNext()) {
            Map<String, Object> message = iterator.next();
            if (String.valueOf(id).equals(String.valueOf(message.get("id")))) {
                iterator.remove();
                return message;
            }
        }
        return null;
    }

    private Map<String, Object> result(Map<String, Object> message) {
        if (message.containsKey("error")) {
            Map<String, Object> error = objectMap(message.get("error"));
            var future = McpProtocolProfile.findFutureProtocolVersion(error.toString());
            if (future.isPresent()) {
                throw new ToolInvocationException(
                        "MCP_PROTOCOL_VERSION_PENDING_ADAPTATION",
                        ToolDispatchState.ACKNOWLEDGED,
                        McpProtocolProfile.adaptationNotice(future.orElseThrow()));
            }
            String description = String.valueOf(error.getOrDefault("message", "MCP protocol error"));
            String code = description.toLowerCase(Locale.ROOT).contains("protocol")
                    ? "MCP_PROTOCOL_VERSION_MISMATCH"
                    : "MCP_PROTOCOL_ERROR";
            throw new ToolInvocationException(
                    code, ToolDispatchState.ACKNOWLEDGED, "MCP server returned a protocol error");
        }
        if (!message.containsKey("result")) throw new IllegalStateException("MCP response does not contain a result");
        return objectMap(message.get("result"));
    }

    private void appendFrames(byte[] bytes) {
        for (byte value : bytes) {
            if (value == '\n') {
                byte[] frame = pendingFrame.toByteArray();
                pendingFrame.reset();
                if (frame.length > 0 && frame[frame.length - 1] == '\r') {
                    frame = java.util.Arrays.copyOf(frame, frame.length - 1);
                }
                if (frame.length > 0) {
                    Map<String, Object> message = decode(frame);
                    if (message.containsKey("id")) messages.add(message);
                }
            } else {
                if (pendingFrame.size() >= definition.maxFrameBytes()) {
                    throw new IllegalStateException("MCP stdio inbound frame exceeds its budget");
                }
                pendingFrame.write(value);
            }
        }
    }

    private Map<String, Object> decode(byte[] frame) {
        try {
            String json = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(frame))
                    .toString();
            return mapper.readValue(json, OBJECT_MAP);
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new IllegalStateException("MCP stdio frame is not valid UTF-8", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("MCP stdio frame is not valid JSON-RPC", exception);
        }
    }

    private void ensureOpen(Map<String, String> credentials) {
        if (closed) throw new IllegalStateException("MCP stdio transport is closed");
        List<String> references = credentials.keySet().stream()
                .sorted()
                .toList();
        if (session != null && !session.isClosed()) {
            if (!references.equals(credentialReferences)) {
                throw new SecurityException("MCP stdio credential binding changed within a connection");
            }
            return;
        }
        McpManagedProcessLaunch prepared = launches.prepare(server, identity, credentials);
        try {
            session = executionBroker.openManagedSession(prepared.request());
            launch = prepared;
            credentialReferences = references;
        } catch (RuntimeException exception) {
            prepared.close();
            throw exception;
        }
    }

    private Map<String, Object> objectMap(Object value) {
        return value == null ? Map.of() : mapper.convertValue(value, OBJECT_MAP);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            if (session != null) session.close();
        } finally {
            if (launch != null) launch.close();
            session = null;
            launch = null;
        }
    }
}
