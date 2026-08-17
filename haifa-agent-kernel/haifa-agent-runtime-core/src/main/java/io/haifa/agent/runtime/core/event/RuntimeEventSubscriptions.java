package io.haifa.agent.runtime.core.event;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.AgentRunEventListener;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventSubscription;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Replay-then-tail subscriptions driven by durable reads and coalesced wake-ups. */
public final class RuntimeEventSubscriptions {
    private static final int DRAIN_PAGE_SIZE = 256;

    private final RuntimeEventFeed feed;
    private final RuntimeEventWakeupRegistry wakeups;

    public RuntimeEventSubscriptions(RuntimeEventFeed feed, RuntimeEventWakeupRegistry wakeups) {
        this.feed = Objects.requireNonNull(feed, "feed must not be null");
        this.wakeups = Objects.requireNonNull(wakeups, "wakeups must not be null");
    }

    public RunEventSubscription subscribe(AgentRunId runId, RunEventCursor after, AgentRunEventListener listener) {
        Subscription subscription = new Subscription(runId, after, listener);
        subscription.start();
        return subscription;
    }

    private final class Subscription implements RunEventSubscription {
        private final AgentRunId runId;
        private final AgentRunEventListener listener;
        private final ArrayBlockingQueue<Boolean> signals = new ArrayBlockingQueue<>(1);
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile RunEventCursor cursor;
        private RuntimeEventWakeupRegistry.Registration registration;
        private Thread worker;

        private Subscription(AgentRunId runId, RunEventCursor cursor, AgentRunEventListener listener) {
            this.runId = Objects.requireNonNull(runId, "runId must not be null");
            this.cursor = Objects.requireNonNull(cursor, "cursor must not be null");
            this.listener = Objects.requireNonNull(listener, "listener must not be null");
        }

        private void start() {
            registration = wakeups.register(runId, this::signal);
            worker =
                    Thread.ofVirtual().name("haifa-run-events-" + runId.value()).start(this::run);
            signal();
        }

        private void run() {
            try {
                while (!closed.get()) {
                    signals.take();
                    try {
                        drain();
                    } catch (RuntimeException ignored) {
                        // The cursor advances only after a complete page. Retrying therefore
                        // replays the unacknowledged page without losing committed events.
                        // A timed retry is used only after failure; healthy idle subscriptions
                        // remain entirely wake-up driven and do not poll durable storage.
                        signals.poll(1, TimeUnit.SECONDS);
                        if (!closed.get()) signal();
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        private void drain() {
            while (!closed.get()) {
                var page = feed.page(runId, cursor, DRAIN_PAGE_SIZE);
                for (var event : page.items()) {
                    if (closed.get()) return;
                    listener.onEvent(event);
                }
                cursor = page.nextCursor();
                if (!page.hasMore()) return;
            }
        }

        private void signal() {
            signals.offer(Boolean.TRUE);
        }

        @Override
        public boolean closed() {
            return closed.get();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (registration != null) registration.close();
            if (worker != null) worker.interrupt();
            signals.clear();
        }
    }
}
