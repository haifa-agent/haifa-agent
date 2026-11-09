package io.haifa.coding.host.internal;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Per-logical-client protocol context; it grants no Run or writer ownership. */
public final class CodingHostClients {
    public static final int DEFAULT_MAXIMUM_CLIENTS = 16;

    private final IdentifierGenerator identifiers;
    private final TimeProvider time;
    private final Duration idleTimeout;
    private final int maximumClients;
    private final Consumer<String> expiredClient;

    private record Context(Instant lastSeen, java.util.Set<String> optionalCapabilities) {}

    private final Map<String, Context> clients = new LinkedHashMap<>();
    private boolean closed;

    public CodingHostClients(IdentifierGenerator identifiers, TimeProvider time, Duration idleTimeout) {
        this(identifiers, time, idleTimeout, DEFAULT_MAXIMUM_CLIENTS, ignored -> {});
    }

    public CodingHostClients(
            IdentifierGenerator identifiers,
            TimeProvider time,
            Duration idleTimeout,
            int maximumClients,
            Consumer<String> expiredClient) {
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout must not be null");
        this.expiredClient = Objects.requireNonNull(expiredClient, "expiredClient must not be null");
        if (idleTimeout.isNegative() || idleTimeout.isZero() || maximumClients < 1) {
            throw new IllegalArgumentException("client context limits must be positive");
        }
        this.maximumClients = maximumClients;
    }

    public synchronized String initialize() {
        return initialize(java.util.Set.of());
    }

    public synchronized String initialize(java.util.Set<String> optionalCapabilities) {
        if (closed) throw CodingHostException.conflict("HOST_SHUTTING_DOWN", "Host is shutting down");
        expireIdle();
        if (clients.size() >= maximumClients) {
            throw CodingHostException.conflict("HOST_CLIENT_LIMIT", "Too many active protocol clients");
        }
        String clientId = identifiers.nextValue();
        clients.put(clientId, new Context(time.now(), java.util.Set.copyOf(optionalCapabilities)));
        return clientId;
    }

    public synchronized void require(String clientId) {
        if (closed) throw CodingHostException.conflict("HOST_SHUTTING_DOWN", "Host is shutting down");
        expireIdle();
        Context context = clients.get(clientId);
        if (context == null) {
            throw CodingHostException.conflict("CLIENT_CONTEXT_REQUIRED", "initialize is required for this client");
        }
        clients.put(clientId, new Context(time.now(), context.optionalCapabilities()));
    }

    /** Checks an existing observation without treating an SSE heartbeat as client renewal. */
    public synchronized void requireObservation(String clientId) {
        if (closed) throw CodingHostException.conflict("HOST_SHUTTING_DOWN", "Host is shutting down");
        expireIdle();
        if (!clients.containsKey(clientId))
            throw CodingHostException.conflict("CLIENT_CONTEXT_REQUIRED", "initialize is required for this client");
    }

    public synchronized void requireOptional(String clientId, String capability) {
        require(clientId);
        if (!clients.get(clientId).optionalCapabilities().contains(capability)) {
            throw CodingHostException.unsupported("Optional capability was not negotiated");
        }
    }

    public synchronized java.util.Set<String> capabilities(String clientId) {
        requireObservation(clientId);
        return clients.get(clientId).optionalCapabilities();
    }

    public synchronized void close() {
        closed = true;
        clients.clear();
    }

    public synchronized void remove(String clientId) {
        if (clients.remove(clientId) != null) expiredClient.accept(clientId);
    }

    private void expireIdle() {
        Instant floor = time.now().minus(idleTimeout);
        var expired = clients.entrySet().stream()
                .filter(entry -> entry.getValue().lastSeen().isBefore(floor))
                .map(Map.Entry::getKey)
                .toList();
        for (String id : expired) {
            clients.remove(id);
            expiredClient.accept(id);
        }
    }
}
