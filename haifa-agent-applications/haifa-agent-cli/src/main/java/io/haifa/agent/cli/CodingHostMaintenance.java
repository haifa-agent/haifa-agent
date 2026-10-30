package io.haifa.agent.cli;

import io.haifa.agent.application.project.product.coding.CodingSessionQuery;
import io.haifa.agent.application.project.product.coding.CodingSessionService;
import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.project.domain.ProjectId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded application-owner maintenance: one non-overlapping reconcile sweep per tick, no UI required. */
final class CodingHostMaintenance implements AutoCloseable {
    static final int SCAN_LIMIT = 100;
    static final Duration DEFAULT_INTERVAL = Duration.ofMillis(250);

    private final ProjectId projectId;
    private final CodingSessionService sessions;
    private final CodingSessionClient client;
    private final ScheduledExecutorService executor;
    private final long intervalMillis;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Optional<io.haifa.agent.application.project.product.coding.CodingSessionCursor> cursor =
            Optional.empty();

    CodingHostMaintenance(ProjectId projectId, CodingSessionService sessions, CodingSessionClient client) {
        this(projectId, sessions, client, DEFAULT_INTERVAL);
    }

    CodingHostMaintenance(
            ProjectId projectId, CodingSessionService sessions, CodingSessionClient client, Duration interval) {
        this.projectId = java.util.Objects.requireNonNull(projectId, "projectId must not be null");
        this.sessions = java.util.Objects.requireNonNull(sessions, "sessions must not be null");
        this.client = java.util.Objects.requireNonNull(client, "client must not be null");
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "haifa-coding-host-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        this.intervalMillis = Math.max(50, interval.toMillis());
    }

    synchronized void start() {
        if (closed.get() || executor.isShutdown()) return;
        executor.scheduleWithFixedDelay(this::tick, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    void tick() {
        if (closed.get() || !running.compareAndSet(false, true)) return;
        try {
            CodingSessionQuery query = new CodingSessionQuery(Optional.empty(), cursor, SCAN_LIMIT);
            var page = sessions.listSessionsForRecovery(projectId, query);
            for (var summary : new ArrayList<>(page.items())) {
                if (closed.get()) return;
                try {
                    client.reconcile(summary.sessionId());
                } catch (RuntimeException ignored) {
                    // Existing product semantics remain authoritative; a later tick can retry the same bounded page.
                }
            }
            cursor = page.nextCursor();
            if (cursor.isEmpty()) cursor = Optional.empty();
        } catch (RuntimeException ignored) {
            // No second state machine: failed maintenance does not fabricate Run state.
        } finally {
            running.set(false);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
