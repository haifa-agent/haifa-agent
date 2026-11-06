package io.haifa.coding.host.internal;

import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunOutputCursor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded process-local notification observations; closing one never touches Run ownership. */
public final class CodingHostSubscriptions {
    public static final int DEFAULT_MAXIMUM_SUBSCRIPTIONS = 32;
    public static final int DEFAULT_QUEUE_CAPACITY = 256;
    public static final Duration DEFAULT_ATTACH_TIMEOUT = Duration.ofSeconds(30);

    private final CodingSessionClient client;
    private final io.haifa.agent.execution.api.ToolOutputPreviewPublisher previews;
    private final int maximumSubscriptions;
    private final int queueCapacity;
    private final TimeProvider time;
    private final Duration attachTimeout;
    private final IdentifierGenerator identifiers;
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicBoolean closing = new AtomicBoolean();

    public CodingHostSubscriptions(CodingSessionClient client) {
        this(
                client,
                DEFAULT_MAXIMUM_SUBSCRIPTIONS,
                DEFAULT_QUEUE_CAPACITY,
                () -> Instant.now(),
                DEFAULT_ATTACH_TIMEOUT,
                new UuidV7IdentifierGenerator());
    }

    public CodingHostSubscriptions(CodingSessionClient client, int maximumSubscriptions, int queueCapacity) {
        this(
                client,
                maximumSubscriptions,
                queueCapacity,
                () -> Instant.now(),
                DEFAULT_ATTACH_TIMEOUT,
                new UuidV7IdentifierGenerator());
    }

    public CodingHostSubscriptions(
            CodingSessionClient client,
            int maximumSubscriptions,
            int queueCapacity,
            TimeProvider time,
            Duration attachTimeout,
            IdentifierGenerator identifiers) {
        this(
                client,
                maximumSubscriptions,
                queueCapacity,
                time,
                attachTimeout,
                identifiers,
                io.haifa.agent.execution.api.ToolOutputPreviewPublisher.noop());
    }

    public CodingHostSubscriptions(
            CodingSessionClient client,
            int maximumSubscriptions,
            int queueCapacity,
            TimeProvider time,
            Duration attachTimeout,
            IdentifierGenerator identifiers,
            io.haifa.agent.execution.api.ToolOutputPreviewPublisher previews) {
        this.previews = Objects.requireNonNull(previews, "previews must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.attachTimeout = Objects.requireNonNull(attachTimeout, "attachTimeout must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        if (maximumSubscriptions < 1 || queueCapacity < 1 || attachTimeout.isNegative() || attachTimeout.isZero()) {
            throw new IllegalArgumentException("subscription limits must be positive");
        }
        this.maximumSubscriptions = maximumSubscriptions;
        this.queueCapacity = queueCapacity;
    }

    public String subscribeEvents(String clientId, AgentSessionId sessionId, AgentRunId runId, RunEventCursor after) {
        Subscription subscription = reserve(clientId, sessionId, runId, "run-event");
        try {
            subscription.install(client.subscribe(runId, after, event -> offerEvent(subscription, event)));
            return subscription.id;
        } catch (RuntimeException exception) {
            discard(subscription);
            throw exception;
        }
    }

    public String subscribeOutput(String clientId, AgentSessionId sessionId, AgentRunId runId, RunOutputCursor after) {
        Subscription subscription = reserve(clientId, sessionId, runId, "output");
        try {
            subscription.install(client.subscribeOutput(runId, after, event -> offerOutput(subscription, event)));
            return subscription.id;
        } catch (RuntimeException exception) {
            discard(subscription);
            throw exception;
        }
    }

    public io.haifa.coding.api.CodingProtocol.Subscribed subscribePresentation(
            String clientId,
            AgentSessionId sessionId,
            java.util.function.Supplier<
                            io.haifa.agent.application.project.product.coding.presentation
                                    .CodingPresentationObservation>
                    factory) {
        Subscription subscription = reserve(clientId, sessionId, null, "presentation");
        try {
            var observation = factory.get();
            subscription.presentation = observation;
            subscription.install(observation);
            return new io.haifa.coding.api.CodingProtocol.Subscribed(subscription.id, observation.initial());
        } catch (RuntimeException failure) {
            discard(subscription);
            throw failure;
        }
    }

    public void refreshPresentations() {
        for (var subscription : List.copyOf(subscriptions.values())) {
            if (subscription.presentation != null) subscription.presentation.refreshConsent();
        }
    }

    public boolean previewAvailable() {
        return previews != io.haifa.agent.execution.api.ToolOutputPreviewPublisher.noop();
    }

    public String subscribePreview(String clientId, AgentSessionId sessionId, AgentRunId runId) {
        Subscription subscription = reserve(clientId, sessionId, runId, "tool-preview");
        CodingHostPreviewStream stream = new CodingHostPreviewStream();
        try {
            subscription.install(previews.subscribe(runId, preview -> {
                if (!runId.equals(preview.runId())) {
                    discard(subscription);
                    return;
                }
                try {
                    var safe = stream.accept(preview);
                    if (safe != null) offer(subscription, CodingHostWire.preview(safe, sessionId));
                } catch (IllegalStateException overflow) {
                    // An ambiguous/over-budget fragment drops only observation, never emits an unsafe suffix.
                    discard(subscription);
                }
            }));
            return subscription.id;
        } catch (RuntimeException failure) {
            discard(subscription);
            throw failure;
        }
    }

    private Subscription reserve(String clientId, AgentSessionId sessionId, AgentRunId runId, String type) {
        expirePending();
        if (closing.get()) {
            throw CodingHostException.conflict("HOST_SHUTTING_DOWN", "Host is shutting down");
        }
        int count = active.incrementAndGet();
        if (count > maximumSubscriptions) {
            active.decrementAndGet();
            throw CodingHostException.conflict("HOST_SUBSCRIPTION_LIMIT", "Too many active subscriptions");
        }
        String id = "sub-" + identifiers.nextValue();
        Subscription subscription = new Subscription(id, clientId, sessionId, runId, type, queueCapacity, time.now());
        subscriptions.put(id, subscription);
        if (closing.get()) {
            discard(subscription);
            throw CodingHostException.conflict("HOST_SHUTTING_DOWN", "Host is shutting down");
        }
        return subscription;
    }

    public Subscription attach(String clientId, String id) {
        expirePending();
        Subscription subscription = subscriptions.get(id);
        if (subscription == null || !subscription.clientId.equals(clientId)) {
            throw CodingHostException.notFound("HOST_SUBSCRIPTION_NOT_FOUND", "Subscription is unavailable");
        }
        if (!subscription.attached.compareAndSet(false, true)) {
            throw CodingHostException.conflict("HOST_SUBSCRIPTION_ATTACHED", "Subscription is already attached");
        }
        return subscription;
    }

    public boolean unsubscribe(String clientId, String id) {
        Subscription subscription = subscriptions.get(id);
        if (subscription == null || !subscription.clientId.equals(clientId)) return false;
        discard(subscription);
        return true;
    }

    public void closeClient(String clientId) {
        for (Subscription subscription : List.copyOf(subscriptions.values())) {
            if (subscription.clientId.equals(clientId)) discard(subscription);
        }
    }

    public void closeAll() {
        closing.set(true);
        List<Subscription> copy = new ArrayList<>(subscriptions.values());
        copy.forEach(this::discard);
    }

    private void expirePending() {
        Instant floor = time.now().minus(attachTimeout);
        for (Subscription subscription : List.copyOf(subscriptions.values())) {
            if (!subscription.attached() && subscription.createdAt.isBefore(floor)) {
                discard(subscription);
            }
        }
    }

    private void discard(Subscription subscription) {
        if (!subscription.closeObservation()) return;
        subscriptions.remove(subscription.id, subscription);
        active.decrementAndGet();
    }

    private void offerEvent(Subscription subscription, AgentRunEvent event) {
        if (subscription.closed()
                || !subscription.sessionId.equals(event.sessionId())
                || !subscription.runId.equals(event.runId())) {
            discard(subscription);
            return;
        }
        offer(subscription, CodingHostWire.event(event));
    }

    private void offerOutput(Subscription subscription, AgentRunOutputEvent event) {
        if (subscription.closed() || !subscription.runId.equals(event.runId())) {
            discard(subscription);
            return;
        }
        offer(subscription, CodingHostWire.output(event, subscription.sessionId));
    }

    private void offer(Subscription subscription, Map<String, Object> value) {
        if (subscription.closed()) return;
        if (!subscription.queue.offer(value)) {
            // A slow observer loses its observation, never the Run. The client must reopen a snapshot/cursor.
            discard(subscription);
        }
    }

    public final class Subscription {
        private final String id;
        private final String clientId;
        private final AgentSessionId sessionId;
        private final AgentRunId runId;
        private final String type;
        private final Instant createdAt;
        private final BlockingQueue<Map<String, Object>> queue;
        private final AtomicBoolean attached = new AtomicBoolean();
        private volatile boolean closed;
        private AutoCloseable handle;
        private volatile io.haifa.agent.application.project.product.coding.presentation.CodingPresentationObservation
                presentation;

        private Subscription(
                String id,
                String clientId,
                AgentSessionId sessionId,
                AgentRunId runId,
                String type,
                int capacity,
                Instant createdAt) {
            this.id = id;
            this.clientId = clientId;
            this.sessionId = sessionId;
            this.runId = runId;
            this.type = type;
            this.createdAt = createdAt;
            this.queue = new ArrayBlockingQueue<>(capacity);
        }

        public synchronized void install(AutoCloseable handle) {
            if (closed) {
                closeQuietly(handle);
                return;
            }
            if (this.handle != null) {
                closeQuietly(handle);
                return;
            }
            this.handle = handle;
        }

        public synchronized boolean closeObservation() {
            if (closed) return false;
            closed = true;
            AutoCloseable current = handle;
            handle = null;
            closeQuietly(current);
            return true;
        }

        public String id() {
            return id;
        }

        public String type() {
            return type;
        }

        public AgentRunId runId() {
            return runId;
        }

        public AgentSessionId sessionId() {
            return sessionId;
        }

        public Object poll(long timeout, TimeUnit unit) throws InterruptedException {
            if (presentation != null) {
                presentation.refreshConsent();
                var envelope = presentation.poll(Duration.ofNanos(unit.toNanos(timeout)));
                if (envelope == null) return null;
                if (envelope instanceof io.haifa.coding.api.CodingProtocol.Resync) discard(this);
                return envelope;
            }
            return queue.poll(timeout, unit);
        }

        public boolean closed() {
            return closed;
        }

        public boolean attached() {
            return attached.get();
        }
    }

    private static void closeQuietly(AutoCloseable handle) {
        if (handle == null) return;
        try {
            handle.close();
        } catch (Exception ignored) {
            // Observation cleanup is best effort and never changes Run state.
        }
    }
}
