package io.haifa.agent.runtime.core.lifecycle;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class RunAwaiter {
    private final ConcurrentHashMap<AgentRunId, Object> monitors = new ConcurrentHashMap<>();

    public void signal(AgentRunId runId) {
        Object monitor = monitors.computeIfAbsent(runId, ignored -> new Object());
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    public <T> T await(AgentRunId runId, Supplier<T> snapshot, java.util.function.Predicate<T> terminal)
            throws InterruptedException {
        Object monitor = monitors.computeIfAbsent(runId, ignored -> new Object());
        synchronized (monitor) {
            T value;
            while (!terminal.test(value = snapshot.get())) monitor.wait();
            return value;
        }
    }

    public <T> Optional<T> await(
            AgentRunId runId, Duration timeout, Supplier<T> snapshot, java.util.function.Predicate<T> terminal)
            throws InterruptedException {
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        long deadline = System.nanoTime() + timeout.toNanos();
        Object monitor = monitors.computeIfAbsent(runId, ignored -> new Object());
        synchronized (monitor) {
            T value;
            while (!terminal.test(value = snapshot.get())) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return Optional.empty();
                long millis = Math.max(1, remaining / 1_000_000L);
                monitor.wait(millis);
            }
            return Optional.of(value);
        }
    }
}
