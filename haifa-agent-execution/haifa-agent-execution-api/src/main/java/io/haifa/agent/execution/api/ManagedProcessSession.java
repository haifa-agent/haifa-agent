package io.haifa.agent.execution.api;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface ManagedProcessSession extends AutoCloseable {
    ManagedProcessSessionId id();

    void write(ProcessInputChunk input);

    Optional<ProcessOutputChunk> read(Duration timeout);

    CompletableFuture<ProcessExit> exit();

    boolean cancel();

    boolean isClosed();

    @Override
    void close();
}
