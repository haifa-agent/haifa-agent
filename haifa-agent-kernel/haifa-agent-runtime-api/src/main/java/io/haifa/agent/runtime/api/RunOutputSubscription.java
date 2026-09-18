package io.haifa.agent.runtime.api;

/**
 * Closeable subscription to transient, Run-scoped model output.
 *
 * <p>The subscription is process-local. Closing it releases the listener immediately and is
 * idempotent.
 */
public interface RunOutputSubscription extends AutoCloseable {
    boolean closed();

    @Override
    void close();
}
