package io.haifa.agent.sandbox.api;

import io.haifa.agent.execution.api.ProcessExit;
import io.haifa.agent.execution.api.ProcessInputChunk;
import io.haifa.agent.execution.api.ProcessOutputChunk;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface SandboxManagedProcess extends AutoCloseable {
    Instant startedAt();

    void write(ProcessInputChunk input);

    Optional<ProcessOutputChunk> read(Duration timeout);

    CompletableFuture<ProcessExit> exit();

    int observedProcessCount();

    boolean cancel();

    @Override
    void close();
}
