package io.haifa.agent.runtime.core.lifecycle;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class RunAwaiter {
    private final ConcurrentHashMap<AgentRunId, WaitState> states = new ConcurrentHashMap<>();

    public void signal(AgentRunId runId) {
        WaitState state = states.computeIfAbsent(runId, ignored -> new WaitState());
        synchronized (state) {
            state.sequence++;
            state.notifyAll();
        }
    }

    public <T> T await(AgentRunId runId, Supplier<T> snapshot, java.util.function.Predicate<T> terminal)
            throws InterruptedException {
        WaitState state = states.computeIfAbsent(runId, ignored -> new WaitState());
        while (true) {
            long observedSequence = sequence(state);
            T value = snapshot.get();
            if (terminal.test(value)) return value;
            synchronized (state) {
                if (state.sequence == observedSequence) state.wait();
            }
        }
    }

    public <T> Optional<T> await(
            AgentRunId runId, Duration timeout, Supplier<T> snapshot, java.util.function.Predicate<T> terminal)
            throws InterruptedException {
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        long deadlineMillis = System.currentTimeMillis() + timeout.toMillis();
        WaitState state = states.computeIfAbsent(runId, ignored -> new WaitState());
        while (true) {
            long observedSequence = sequence(state);
            T value = snapshot.get();
            if (terminal.test(value)) return Optional.of(value);
            long remainingMillis = deadlineMillis - System.currentTimeMillis();
            if (remainingMillis <= 0) return Optional.empty();
            synchronized (state) {
                if (state.sequence == observedSequence) {
                    state.wait(Math.max(1, remainingMillis));
                }
            }
        }
    }

    private static long sequence(WaitState state) {
        synchronized (state) {
            return state.sequence;
        }
    }

    private static final class WaitState {
        private long sequence;
    }
}
