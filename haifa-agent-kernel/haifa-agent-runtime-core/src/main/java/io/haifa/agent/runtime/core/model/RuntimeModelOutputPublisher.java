package io.haifa.agent.runtime.core.model;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.runtime.api.RunOutputSubscription;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Run-scoped, bounded and strictly process-local model output channel.
 *
 * <p>No event emitted by this class is appended to the Runtime journal, Outbox, Checkpoint or
 * JSONL projection. Listener failures are isolated from the AgentLoop.
 */
public final class RuntimeModelOutputPublisher {
    static final int DEFAULT_MAXIMUM_BUFFERED_EVENTS = 2_048;
    static final int DEFAULT_MAXIMUM_BUFFERED_TEXT_CHARACTERS = 262_144;

    private final TimeProvider time;
    private final int maximumBufferedEvents;
    private final int maximumBufferedTextCharacters;
    private final ConcurrentHashMap<AgentRunId, RunChannel> channels = new ConcurrentHashMap<>();

    public RuntimeModelOutputPublisher(TimeProvider time) {
        this(time, DEFAULT_MAXIMUM_BUFFERED_EVENTS, DEFAULT_MAXIMUM_BUFFERED_TEXT_CHARACTERS);
    }

    RuntimeModelOutputPublisher(TimeProvider time, int maximumBufferedEvents, int maximumBufferedTextCharacters) {
        this.time = Objects.requireNonNull(time, "time must not be null");
        if (maximumBufferedEvents < 1) {
            throw new IllegalArgumentException("maximumBufferedEvents must be positive");
        }
        if (maximumBufferedTextCharacters < 1) {
            throw new IllegalArgumentException("maximumBufferedTextCharacters must be positive");
        }
        this.maximumBufferedEvents = maximumBufferedEvents;
        this.maximumBufferedTextCharacters = maximumBufferedTextCharacters;
    }

    public void started(AgentRunId runId, String callId, int attempt, int iteration) {
        RunChannel channel = channel(runId);
        channel.started(new Generation(callId, attempt));
        Generation failed = channel.removeFailed(iteration);
        if (failed != null) {
            emit(
                    channel,
                    failed.callId(),
                    failed.callId(),
                    failed.attempt(),
                    AgentRunOutputEventType.RUN_OUTPUT_SUPERSEDED,
                    "");
        }
        emit(channel, callId, callId, attempt, AgentRunOutputEventType.RUN_OUTPUT_STARTED, "");
    }

    public void content(AgentRunId runId, String callId, int attempt, String delta) {
        emit(
                channel(runId),
                callId,
                callId,
                attempt,
                AgentRunOutputEventType.ASSISTANT_TEXT_DELTA,
                Objects.requireNonNull(delta, "delta must not be null"));
    }

    public void committed(AgentRunId runId, String callId, int attempt, int iteration) {
        RunChannel channel = channel(runId);
        channel.removeFailed(iteration);
        emit(channel, callId, callId, attempt, AgentRunOutputEventType.ASSISTANT_TEXT_COMMITTED, "");
        closeIfTerminalAndIdle(runId, channel, new Generation(callId, attempt));
    }

    public void failed(AgentRunId runId, String callId, int attempt, int iteration) {
        RunChannel channel = channel(runId);
        channel.failed(iteration, new Generation(callId, attempt));
        emit(channel, callId, callId, attempt, AgentRunOutputEventType.RUN_OUTPUT_FAILED, "");
        closeIfTerminalAndIdle(runId, channel, new Generation(callId, attempt));
    }

    public List<AgentRunOutputEvent> after(AgentRunId runId, RunOutputCursor after, int limit) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(after, "after must not be null");
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("limit must be between 1 and 10000");
        }
        RunChannel channel = channels.get(runId);
        return channel == null ? List.of() : channel.after(after.sequence(), limit);
    }

    public RunOutputSubscription subscribe(AgentRunId runId, RunOutputCursor after, AgentRunOutputListener listener) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(after, "after must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        RunChannel channel = channel(runId);
        Subscriber subscriber = channel.subscribe(after.sequence(), listener);
        Subscription subscription = new Subscription(runId, channel, subscriber);
        subscriber.activate();
        return subscription;
    }

    /** Releases the active-Run buffer and every listener after a terminal state is committed. */
    public void closeRun(AgentRunId runId) {
        RunChannel channel = channels.remove(Objects.requireNonNull(runId, "runId must not be null"));
        if (channel != null) channel.close();
    }

    /**
     * Marks a durable terminal transition without racing the final committed/failed output signal.
     */
    public void markRunTerminal(AgentRunId runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        RunChannel channel = channels.get(runId);
        if (channel != null && channel.markTerminal()) {
            closeRun(runId);
        }
    }

    int activeRunCount() {
        return channels.size();
    }

    int subscriberCount(AgentRunId runId) {
        RunChannel channel = channels.get(runId);
        return channel == null ? 0 : channel.subscriberCount();
    }

    private RunChannel channel(AgentRunId runId) {
        return channels.computeIfAbsent(
                Objects.requireNonNull(runId, "runId must not be null"),
                ignored -> new RunChannel(runId, maximumBufferedEvents, maximumBufferedTextCharacters));
    }

    private void closeIfTerminalAndIdle(AgentRunId runId, RunChannel channel, Generation generation) {
        if (channel.finished(generation)) {
            channels.remove(runId, channel);
            channel.close();
        }
    }

    private void emit(
            RunChannel channel,
            String callId,
            String generationId,
            int attempt,
            AgentRunOutputEventType type,
            String text) {
        channel.emit(sequence -> new AgentRunOutputEvent(
                channel.runId(), callId, generationId, attempt, sequence, type, text, time.now()));
    }

    private final class Subscription implements RunOutputSubscription {
        private final AgentRunId runId;
        private final RunChannel channel;
        private final Subscriber subscriber;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Subscription(AgentRunId runId, RunChannel channel, Subscriber subscriber) {
            this.runId = runId;
            this.channel = channel;
            this.subscriber = subscriber;
        }

        @Override
        public boolean closed() {
            return closed.get() || subscriber.closed();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            subscriber.close();
            channel.remove(subscriber);
            if (channel.closed()) channels.remove(runId, channel);
        }
    }

    @FunctionalInterface
    private interface EventFactory {
        AgentRunOutputEvent create(long sequence);
    }

    private static final class RunChannel {
        private final int maximumBufferedEvents;
        private final int maximumBufferedTextCharacters;
        private final AgentRunId runId;
        private final ArrayDeque<AgentRunOutputEvent> buffer = new ArrayDeque<>();
        private final ArrayDeque<Delivery> pendingDeliveries = new ArrayDeque<>();
        private final List<Subscriber> subscribers = new ArrayList<>();
        private final Map<Integer, Generation> failedGenerations = new HashMap<>();
        private final java.util.Set<Generation> activeGenerations = new java.util.HashSet<>();
        private long nextSequence = 1;
        private int bufferedTextCharacters;
        private boolean dispatching;
        private boolean terminal;
        private boolean closed;

        private RunChannel(AgentRunId runId, int maximumBufferedEvents, int maximumBufferedTextCharacters) {
            this.runId = runId;
            this.maximumBufferedEvents = maximumBufferedEvents;
            this.maximumBufferedTextCharacters = maximumBufferedTextCharacters;
        }

        synchronized AgentRunId runId() {
            return runId;
        }

        void emit(EventFactory factory) {
            boolean drain;
            synchronized (this) {
                if (closed) return;
                AgentRunOutputEvent event = factory.create(nextSequence++);
                buffer.addLast(event);
                bufferedTextCharacters += event.textDelta().length();
                trim();
                pendingDeliveries.addLast(new Delivery(event, List.copyOf(subscribers)));
                drain = !dispatching;
                if (drain) dispatching = true;
            }
            if (drain) drainDeliveries();
        }

        synchronized List<AgentRunOutputEvent> after(long sequence, int limit) {
            if (closed) return List.of();
            return buffer.stream()
                    .filter(event -> event.sequence() > sequence)
                    .limit(limit)
                    .toList();
        }

        synchronized Subscriber subscribe(long sequence, AgentRunOutputListener listener) {
            Subscriber subscriber = new Subscriber(sequence, listener);
            if (closed) {
                subscriber.close();
                return subscriber;
            }
            buffer.stream().filter(event -> event.sequence() > sequence).forEach(subscriber::offer);
            subscribers.add(subscriber);
            return subscriber;
        }

        synchronized void remove(Subscriber subscriber) {
            subscribers.remove(subscriber);
        }

        synchronized int subscriberCount() {
            return subscribers.size();
        }

        synchronized void failed(int iteration, Generation generation) {
            if (!closed) failedGenerations.put(iteration, generation);
        }

        synchronized void started(Generation generation) {
            if (!closed) activeGenerations.add(generation);
        }

        synchronized Generation removeFailed(int iteration) {
            return failedGenerations.remove(iteration);
        }

        synchronized boolean markTerminal() {
            terminal = true;
            return activeGenerations.isEmpty();
        }

        synchronized boolean finished(Generation generation) {
            activeGenerations.remove(generation);
            return terminal && activeGenerations.isEmpty();
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            subscribers.forEach(Subscriber::close);
            subscribers.clear();
            failedGenerations.clear();
            activeGenerations.clear();
            buffer.clear();
            pendingDeliveries.clear();
            bufferedTextCharacters = 0;
        }

        synchronized boolean closed() {
            return closed;
        }

        private void trim() {
            while (buffer.size() > maximumBufferedEvents || bufferedTextCharacters > maximumBufferedTextCharacters) {
                AgentRunOutputEvent removed = buffer.removeFirst();
                bufferedTextCharacters -= removed.textDelta().length();
            }
        }

        private void drainDeliveries() {
            while (true) {
                Delivery delivery;
                synchronized (this) {
                    if (closed || pendingDeliveries.isEmpty()) {
                        dispatching = false;
                        return;
                    }
                    delivery = pendingDeliveries.removeFirst();
                }
                delivery.subscribers().forEach(subscriber -> subscriber.offer(delivery.event()));
            }
        }

        private record Delivery(AgentRunOutputEvent event, List<Subscriber> subscribers) {}
    }

    /**
     * Orders replay and live delivery without holding the RunChannel monitor while user code runs.
     */
    private static final class Subscriber {
        private final long afterSequence;
        private final AgentRunOutputListener listener;
        private final TreeMap<Long, AgentRunOutputEvent> pending = new TreeMap<>(Comparator.naturalOrder());
        private boolean active;
        private boolean delivering;
        private boolean closed;

        private Subscriber(long afterSequence, AgentRunOutputListener listener) {
            this.afterSequence = afterSequence;
            this.listener = listener;
        }

        void offer(AgentRunOutputEvent event) {
            boolean drain;
            synchronized (this) {
                if (closed || event.sequence() <= afterSequence) return;
                pending.putIfAbsent(event.sequence(), event);
                drain = active && !delivering;
                if (drain) delivering = true;
            }
            if (drain) drain();
        }

        void activate() {
            boolean drain;
            synchronized (this) {
                if (closed) return;
                active = true;
                drain = !delivering && !pending.isEmpty();
                if (drain) delivering = true;
            }
            if (drain) drain();
        }

        private void drain() {
            while (true) {
                AgentRunOutputEvent event;
                synchronized (this) {
                    if (closed || pending.isEmpty()) {
                        delivering = false;
                        return;
                    }
                    event = pending.pollFirstEntry().getValue();
                }
                try {
                    listener.onOutput(event);
                } catch (RuntimeException ignored) {
                    // Observers must never affect the AgentLoop or subsequent delivery.
                }
            }
        }

        synchronized void close() {
            closed = true;
            pending.clear();
        }

        synchronized boolean closed() {
            return closed;
        }
    }

    private record Generation(String callId, int attempt) {}
}
