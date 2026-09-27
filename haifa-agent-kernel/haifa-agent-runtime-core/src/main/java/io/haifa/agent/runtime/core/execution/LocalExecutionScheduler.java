package io.haifa.agent.runtime.core.execution;

import io.haifa.agent.core.run.AgentRunId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LocalExecutionScheduler implements ExecutionScheduler, AutoCloseable {
    private final ExecutorService executor;
    private final Object monitor = new Object();
    private final Map<AgentRunId, RunLane> lanes = new HashMap<>();
    private boolean closing;

    public LocalExecutionScheduler() {
        this(Executors.newVirtualThreadPerTaskExecutor());
    }

    public LocalExecutionScheduler(ExecutorService executor) {
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public void submit(AgentRunId runId, Runnable task) {
        submit(runId, task, false);
    }

    @Override
    public void submitAfterCurrent(AgentRunId runId, Runnable task) {
        submit(runId, task, true);
    }

    private void submit(AgentRunId runId, Runnable task, boolean afterCurrent) {
        AgentRunId checkedRunId = Objects.requireNonNull(runId, "runId must not be null");
        TrackedTask scheduled = new TrackedTask(checkedRunId, Objects.requireNonNull(task, "task must not be null"));
        boolean dispatch;
        synchronized (monitor) {
            if (closing) throw new IllegalStateException("process-local execution scheduler is closed");
            RunLane lane = lanes.get(checkedRunId);
            if (lane == null) {
                lanes.put(checkedRunId, new RunLane(scheduled));
                dispatch = true;
            } else {
                if (!afterCurrent) {
                    throw new IllegalStateException("run already has a process-local execution task");
                }
                if (lane.successor != null) {
                    throw new IllegalStateException("run already has a process-local execution successor");
                }
                lane.successor = scheduled;
                dispatch = false;
            }
        }
        if (!dispatch) return;
        try {
            executor.execute(scheduled);
        } catch (RuntimeException | Error failure) {
            discard(scheduled);
            throw failure;
        }
    }

    @Override
    public void cancel(AgentRunId runId) {
        synchronized (monitor) {
            RunLane lane = lanes.get(Objects.requireNonNull(runId, "runId must not be null"));
            if (lane == null) return;
            lane.current.requestCancellation();
            if (lane.successor != null) lane.successor.requestCancellation();
        }
    }

    @Override
    public void close() {
        boolean interrupted = false;
        synchronized (monitor) {
            closing = true;
            while (!lanes.isEmpty()) {
                try {
                    monitor.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }
        executor.close();
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void completed(TrackedTask completed) {
        TrackedTask successor;
        synchronized (monitor) {
            RunLane lane = lanes.get(completed.runId);
            if (lane == null || lane.current != completed) return;
            successor = lane.successor;
            if (successor == null) {
                lanes.remove(completed.runId);
                monitor.notifyAll();
                return;
            }
            lane.current = successor;
            lane.successor = null;
        }
        try {
            executor.execute(successor);
        } catch (RuntimeException | Error failure) {
            discard(successor);
            throw failure;
        }
    }

    private void discard(TrackedTask task) {
        synchronized (monitor) {
            RunLane lane = lanes.get(task.runId);
            if (lane == null) return;
            if (lane.current == task) {
                lanes.remove(task.runId);
                monitor.notifyAll();
            } else if (lane.successor == task) {
                lane.successor = null;
            }
        }
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
                completed(this);
            }
        }

        private void requestCancellation() {
            cancellationRequested.set(true);
            Thread runningThread = runner;
            if (runningThread != null) runningThread.interrupt();
        }
    }

    private static final class RunLane {
        private TrackedTask current;
        private TrackedTask successor;

        private RunLane(TrackedTask current) {
            this.current = current;
        }
    }
}
