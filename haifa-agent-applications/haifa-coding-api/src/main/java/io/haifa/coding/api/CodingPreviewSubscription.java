package io.haifa.coding.api;

import java.time.Duration;

/** Transient, non-replayable Tool preview. Close/unavailability never establishes a Tool or Run outcome. */
public interface CodingPreviewSubscription extends AutoCloseable {
    CodingProtocol.Preview poll(Duration timeout) throws InterruptedException;

    boolean closed();

    String unavailableReason();

    @Override
    void close();
}
