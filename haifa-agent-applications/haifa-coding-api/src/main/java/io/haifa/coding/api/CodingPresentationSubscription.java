package io.haifa.coding.api;

import java.time.Duration;

/** Observation ownership only: closing never cancels a Run or closes its Host owner. */
public interface CodingPresentationSubscription extends AutoCloseable {
    CodingProtocol.Snapshot initial();

    CodingProtocol.Envelope poll(Duration timeout) throws InterruptedException;

    boolean closed();

    @Override
    void close();
}
