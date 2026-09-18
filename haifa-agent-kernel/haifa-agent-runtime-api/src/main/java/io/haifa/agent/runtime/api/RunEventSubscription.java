package io.haifa.agent.runtime.api;

/** Closeable Run-scoped event subscription. */
public interface RunEventSubscription extends AutoCloseable {
    boolean closed();

    @Override
    void close();
}
