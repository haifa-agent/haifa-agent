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
    private final Thread reader;
    private volatile String activeViewId;

    TerminalProductObservation(CodingClient client, CodingProtocol.ConnectionInfo connection, String session) {
        reader = Thread.ofVirtual().name("terminal-product-observation").start(() -> read(client, connection, session));
    }

    CodingProtocol.Snapshot takeSnapshot() {
        return latest.getAndSet(null);
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
                    latest.set(snapshot);
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
                                latest.set(snapshot);
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

    @Override
    public void close() {
        reader.interrupt();
    }
}
