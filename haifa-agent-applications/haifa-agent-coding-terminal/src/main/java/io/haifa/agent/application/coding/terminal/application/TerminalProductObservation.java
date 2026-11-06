package io.haifa.agent.application.coding.terminal.application;

import io.haifa.coding.api.CodingClient;
import io.haifa.coding.api.CodingClientException;
import io.haifa.coding.api.CodingPresentationReducer;
import io.haifa.coding.api.CodingProtocol;
import io.haifa.coding.api.CodingProtocolChecks;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/** An observation worker with a single latest-snapshot mailbox. UI callbacks never perform transport I/O. */
final class TerminalProductObservation implements AutoCloseable {
    private final AtomicReference<CodingProtocol.Snapshot> latest = new AtomicReference<>();
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final java.util.concurrent.ArrayBlockingQueue<CodingProtocol.Entry> activity =
            new java.util.concurrent.ArrayBlockingQueue<>(512);
    private final Thread reader;
    private String activityRun;
    private long activityCursor;
    private volatile String activeViewId;

    TerminalProductObservation(CodingClient client, CodingProtocol.ConnectionInfo connection, String session) {
        reader = Thread.ofVirtual().name("terminal-product-observation").start(() -> read(client, connection, session));
    }

    CodingProtocol.Snapshot takeSnapshot() {
        return latest.getAndSet(null);
    }

    java.util.List<CodingProtocol.Entry> takeActivity(int limit) {
        var entries = new java.util.ArrayList<CodingProtocol.Entry>();
        activity.drainTo(entries, limit);
        return java.util.List.copyOf(entries);
    }

    String takeFailure() {
        return failure.getAndSet(null);
    }

    boolean hasAuthority(String viewId) {
        return viewId.equals(activeViewId);
    }

    private void read(CodingClient client, CodingProtocol.ConnectionInfo connection, String session) {
        int retries = 0;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try (var observation = client.observe(session)) {
                    var snapshot = observation.initial();
                    CodingProtocolChecks.snapshot(snapshot);
                    var scope = snapshot.scope();
                    if (!scope.sessionId().equals(session)
                            || !scope.clientId().equals(connection.clientId())
                            || !scope.hostIncarnation().equals(connection.hostIncarnation())
                            || !"native".equals(scope.source())) {
                        throw new IllegalArgumentException("VIEW_SCOPE_MISMATCH");
                    }
                    activeViewId = scope.viewId();
                    publish(client, session, snapshot);
                    while (!Thread.currentThread().isInterrupted() && !observation.closed()) {
                        var event = observation.poll(Duration.ofSeconds(1));
                        if (event == null) continue;
                        if (event instanceof CodingProtocol.Resync resync) {
                            activeViewId = null;
                            if (!snapshot.scope().viewId().equals(resync.viewId())) {
                                throw new IllegalArgumentException("VIEW_SCOPE_MISMATCH");
                            }
                            break;
                        }
                        if (!(event instanceof CodingProtocol.Update update)) {
                            throw new IllegalArgumentException("INVALID_VIEW_UPDATE");
                        }
                        try {
                            var next = CodingPresentationReducer.apply(snapshot, update);
                            if (next != snapshot) {
                                snapshot = next;
                                publish(client, session, snapshot);
                                retries = 0;
                            }
                        } catch (IllegalArgumentException invalid) {
                            activeViewId = null;
                            if (!"VIEW_GAP_RESYNC".equals(invalid.getMessage())) throw invalid;
                            break;
                        }
                    }
                } finally {
                    activeViewId = null;
                }
                if (!Thread.currentThread().isInterrupted() && retries++ >= 2) {
                    failure.set("PRODUCT_OBSERVATION_INTERRUPTED");
                    return;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (CodingClientException rejected) {
            failure.set(rejected.code());
        } catch (RuntimeException invalid) {
            failure.set("PRODUCT_OBSERVATION_INVALID");
        }
    }

    private void publish(CodingClient client, String session, CodingProtocol.Snapshot snapshot)
            throws InterruptedException {
        latest.set(snapshot);
        var head = snapshot.activity();
        if (head != null && !head.runId().equals(activityRun)) {
            if (activityRun != null) readActivity(client, session, Long.MAX_VALUE);
            activityRun = head.runId();
            activityCursor = 0;
        }
        if (head != null) readActivity(client, session, head.sequence());
    }

    private void readActivity(CodingClient client, String session, long through) throws InterruptedException {
        while (activityCursor < through) {
            var request = new CodingProtocol.ActivityRequest(session, activityRun, activityCursor);
            var page = client.activity(request);
            CodingProtocolChecks.activity(request, page);
            for (var entry : page.entries()) {
                if (entry.sequence() > through) break;
                activity.put(entry);
            }
            activityCursor = Math.min(through, page.nextSequence());
            if (!page.hasMore()) return;
        }
    }

    @Override
    public void close() {
        reader.interrupt();
    }
}
