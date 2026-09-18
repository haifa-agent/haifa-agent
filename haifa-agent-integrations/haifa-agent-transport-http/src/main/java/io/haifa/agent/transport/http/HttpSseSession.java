package io.haifa.agent.transport.http;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.RunEventSubscription;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded SSE connection state. Runtime callbacks only enqueue complete frames and never perform network I/O.
 */
public final class HttpSseSession implements AutoCloseable {
    private final AgentRunId runId;
    private final TrustedCallerContext caller;
    private final RunOperationAuthorizer authorizer;
    private final ContractRuntimeMapper mapper;
    private final HttpJsonCodec json;
    private final ArrayBlockingQueue<SseFrame> frames;
    private final AtomicReference<RunEventSubscription> subscription = new AtomicReference<>();
    private final AtomicReference<SseCloseReason> closeReason = new AtomicReference<>(SseCloseReason.OPEN);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<String> lastWrittenCursor = new AtomicReference<>();

    HttpSseSession(
            AgentRunId runId,
            TrustedCallerContext caller,
            RunOperationAuthorizer authorizer,
            ContractRuntimeMapper mapper,
            HttpJsonCodec json,
            int queueCapacity) {
        this.runId = Objects.requireNonNull(runId);
        this.caller = Objects.requireNonNull(caller);
        this.authorizer = Objects.requireNonNull(authorizer);
        this.mapper = Objects.requireNonNull(mapper);
        this.json = Objects.requireNonNull(json);
        this.frames = new ArrayBlockingQueue<>(queueCapacity);
    }

    void attach(RunEventSubscription value) {
        if (!subscription.compareAndSet(null, Objects.requireNonNull(value))) {
            value.close();
            throw new IllegalStateException("SSE Runtime subscription already attached");
        }
        if (closed.get()) value.close();
    }

    void onEvent(AgentRunEvent event) {
        if (closed.get()) return;
        try {
            var envelope = mapper.event(event);
            SseFrame frame = SseFrame.event(envelope.cursor().value(), envelope.eventType(), json.writeEvent(envelope));
            if (!frames.offer(frame)) close(SseCloseReason.SLOW_CONSUMER);
        } catch (RuntimeException serializationFailure) {
            close(SseCloseReason.SERIALIZATION_FAILED);
        }
    }

    public SseFrame poll(Duration maximumWait) throws InterruptedException {
        Objects.requireNonNull(maximumWait, "maximumWait must not be null");
        if (closed.get()) throw new IllegalStateException("SSE session is closed: " + closeReason.get());
        try {
            authorizer.authorize(caller, RunOperation.SUBSCRIBE_EVENTS, Optional.of(runId.value()), Optional.empty());
        } catch (RuntimeException denied) {
            close(SseCloseReason.AUTHORIZATION_REVOKED);
            throw denied;
        }
        SseFrame frame = frames.poll(maximumWait.toMillis(), TimeUnit.MILLISECONDS);
        return frame == null ? SseFrame.heartbeat() : frame;
    }

    /** Called only after the host confirms that a complete frame was written. */
    public void acknowledgeWritten(SseFrame frame) {
        Objects.requireNonNull(frame);
        frame.id().ifPresent(lastWrittenCursor::set);
    }

    public Optional<String> lastWrittenCursor() {
        return Optional.ofNullable(lastWrittenCursor.get());
    }

    public int queuedFrames() {
        return frames.size();
    }

    public SseCloseReason closeReason() {
        return closeReason.get();
    }

    public boolean closed() {
        return closed.get();
    }

    @Override
    public void close() {
        close(SseCloseReason.CLIENT_DISCONNECTED);
    }

    public void closeNormally() {
        close(SseCloseReason.NORMAL);
    }

    private void close(SseCloseReason reason) {
        if (!closed.compareAndSet(false, true)) return;
        closeReason.compareAndSet(SseCloseReason.OPEN, reason);
        RunEventSubscription current = subscription.get();
        if (current != null) current.close();
        frames.clear();
    }
}
