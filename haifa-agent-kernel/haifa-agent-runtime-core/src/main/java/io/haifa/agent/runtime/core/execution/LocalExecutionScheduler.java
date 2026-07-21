package io.haifa.agent.runtime.core.execution;

import io.haifa.agent.core.run.AgentRunId;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalExecutionScheduler implements ExecutionScheduler, AutoCloseable {
    private final ExecutorService executor;

    public LocalExecutionScheduler() {
        this(Executors.newVirtualThreadPerTaskExecutor());
    }

    public LocalExecutionScheduler(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public void submit(AgentRunId runId, Runnable task) {
        executor.submit(task);
    }

    @Override
    public void close() {
        executor.close();
    }
}
