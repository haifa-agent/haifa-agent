package io.haifa.agent.cli.remote;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.RunEventSubscription;
import io.haifa.agent.runtime.api.RunOutputSubscription;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Bounded server-sent-events observation for one Run; closing it never changes Run ownership. */
final class RemoteSseSubscription implements RunEventSubscription, RunOutputSubscription {
    private static final int MAX_SSE_LINE_CHARACTERS = 256 * 1024;
    private static final int MAX_SSE_DATA_CHARACTERS = 1024 * 1024;

    private final RemoteRpcTransport transport;
    private final ExecutorService executor;
    private final String subscriptionId;
    private final AgentSessionId sessionId;
    private final AgentRunId runId;
    private final String type;
    private final Consumer<JsonNode> onData;
    private final Consumer<ProjectProductException> onFailure;
    private final Consumer<RemoteSseSubscription> onClosed;
    private final Object lock = new Object();
    private volatile boolean closed;
    private volatile InputStream stream;
    private volatile Future<?> worker;

    RemoteSseSubscription(
            RemoteRpcTransport transport,
            ExecutorService executor,
            String subscriptionId,
            AgentSessionId sessionId,
            AgentRunId runId,
            String type,
            Consumer<JsonNode> onData,
            Consumer<ProjectProductException> onFailure,
            Consumer<RemoteSseSubscription> onClosed) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId must not be null");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.onData = Objects.requireNonNull(onData, "onData must not be null");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure must not be null");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed must not be null");
    }

    String subscriptionId() {
        return subscriptionId;
    }

    AgentSessionId sessionId() {
        return sessionId;
    }

    AgentRunId runId() {
        return runId;
    }

    String type() {
        return type;
    }

    void start() {
        synchronized (lock) {
            if (closed || worker != null) {
                return;
            }
            worker = executor.submit(this::read);
        }
    }

    private void read() {
        StringBuilder data = new StringBuilder();
        String eventName = null;
        try {
            HttpResponse<InputStream> response = transport.openEventStream(subscriptionId);
            InputStream body = response.body();
            synchronized (lock) {
                if (closed) {
                    closeQuietly(body);
                    return;
                }
                stream = body;
            }
            try (PushbackReader reader = new PushbackReader(new InputStreamReader(body, StandardCharsets.UTF_8), 1)) {
                String line;
                while (!closed && (line = nextLine(reader)) != null) {
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            dispatch(eventName, data.toString());
                        }
                        data.setLength(0);
                        eventName = null;
                        continue;
                    }
                    if (line.startsWith(":")) {
                        continue;
                    }
                    int separator = line.indexOf(':');
                    String field = separator < 0 ? line : line.substring(0, separator);
                    String value = separator < 0 ? "" : line.substring(separator + 1);
                    if (value.startsWith(" ")) {
                        value = value.substring(1);
                    }
                    if ("event".equals(field)) {
                        eventName = value;
                    } else if ("data".equals(field)) {
                        int extra = (data.length() > 0 ? 1 : 0) + value.length();
                        if (data.length() + extra > MAX_SSE_DATA_CHARACTERS) {
                            throw RemoteRpcTransport.protocolIncompatible();
                        }
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        data.append(value);
                    }
                }
            }
            if (!closed) {
                fail(new ProjectProductException(
                        "REMOTE_STREAM_CLOSED", "Remote event stream ended before the observation was closed"));
            }
        } catch (IOException exception) {
            if (!closed) {
                fail(new ProjectProductException("REMOTE_STREAM_CLOSED", "Remote event stream is unavailable"));
            }
        } catch (ProjectProductException exception) {
            if (!closed) {
                fail(exception);
            }
        } catch (RuntimeException exception) {
            if (!closed) {
                fail(RemoteRpcTransport.protocolIncompatible());
            }
        } finally {
            closeQuietly(stream);
        }
    }

    private static String nextLine(PushbackReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        boolean read = false;
        while ((value = reader.read()) != -1) {
            read = true;
            char character = (char) value;
            if (character == '\n') {
                return line.toString();
            }
            if (character == '\r') {
                int next = reader.read();
                if (next != '\n' && next != -1) {
                    reader.unread(next);
                }
                return line.toString();
            }
            if (line.length() >= MAX_SSE_LINE_CHARACTERS) {
                throw RemoteRpcTransport.protocolIncompatible();
            }
            line.append(character);
        }
        return read ? line.toString() : null;
    }

    private void dispatch(String eventName, String payload) {
        if (!type.equals(eventName)) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        JsonNode node;
        try {
            node = transport.json().readTree(payload);
        } catch (IOException exception) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        if (node == null || !node.isObject()) {
            throw RemoteRpcTransport.protocolIncompatible();
        }
        if (closed) {
            return;
        }
        onData.accept(node);
    }

    private void fail(ProjectProductException failure) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            onFailure.accept(failure);
            closed = true;
        }
        closeQuietly(stream);
        onClosed.accept(this);
    }

    @Override
    public boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        Future<?> activeWorker;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            activeWorker = worker;
        }
        if (activeWorker != null) {
            activeWorker.cancel(true);
        }
        closeQuietly(stream);
        onClosed.accept(this);
    }

    private static void closeQuietly(InputStream value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (IOException ignored) {
            // Observation cleanup is best effort and never changes Run state.
        }
    }
}
