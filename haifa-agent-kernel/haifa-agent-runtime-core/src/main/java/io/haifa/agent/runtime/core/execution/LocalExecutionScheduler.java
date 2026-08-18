package io.haifa.agent.runtime.core.execution;

import io.haifa.agent.core.run.AgentRunId;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LocalExecutionScheduler implements ExecutionScheduler, AutoCloseable {
    private final ExecutorService executor;
    private final ConcurrentHashMap<AgentRunId, TrackedTask> tasks = new ConcurrentHashMap<>();

    public LocalExecutionScheduler() {
        this(Executors.newVirtualThreadPerTaskExecutor());
    }

    public LocalExecutionScheduler(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public void submit(AgentRunId runId, Runnable task) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(task, "task must not be null");
        TrackedTask scheduled = new TrackedTask(runId, task);
        if (tasks.putIfAbsent(runId, scheduled) != null) {
            throw new IllegalStateException("run already has a process-local execution task");
        }
        try {
            executor.execute(scheduled);
        } catch (RuntimeException | Error failure) {
            tasks.remove(runId, scheduled);
            throw failure;
        }
    }

    @Override
    public void cancel(AgentRunId runId) {
        TrackedTask task = tasks.get(Objects.requireNonNull(runId, "runId must not be null"));
        if (task != null) task.requestCancellation();
    }

    @Override
    public void close() {
        executor.close();
    }

    private final class TrackedTask implements Runnable {
        private final AgentRunId runId;
        private final Runnable delegate;
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private volatile Thread runner;

        private TrackedTask(AgentRunId runId, Runnable delegate) {
            this.runId = runId;
            this.delegate = delegate;
        }

        @Override
        public void run() {
            runner = Thread.currentThread();
            if (cancellationRequested.get()) runner.interrupt();
            try {
                delegate.run();
            } finally {
                runner = null;
                tasks.remove(runId, this);
            }
        }

        private void requestCancellation() {
            cancellationRequested.set(true);
            Thread runningThread = runner;
            if (runningThread != null) runningThread.interrupt();
        }
    }
}
