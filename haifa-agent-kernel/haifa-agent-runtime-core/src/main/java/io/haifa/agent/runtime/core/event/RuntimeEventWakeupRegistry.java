package io.haifa.agent.runtime.core.event;

import io.haifa.agent.core.run.AgentRunId;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/** Run-scoped post-commit wake-up registry. Wake-ups carry no event payload. */
public final class RuntimeEventWakeupRegistry {
    private final ConcurrentHashMap<AgentRunId, CopyOnWriteArraySet<Runnable>> listeners = new ConcurrentHashMap<>();

    public Registration register(AgentRunId runId, Runnable listener) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        CopyOnWriteArraySet<Runnable> runListeners =
                listeners.computeIfAbsent(runId, ignored -> new CopyOnWriteArraySet<>());
        runListeners.add(listener);
        return new Registration() {
            private final java.util.concurrent.atomic.AtomicBoolean closed =
                    new java.util.concurrent.atomic.AtomicBoolean();

            @Override
            public void close() {
                if (!closed.compareAndSet(false, true)) return;
                runListeners.remove(listener);
                if (runListeners.isEmpty()) listeners.remove(runId, runListeners);
            }
        };
    }

    public void wake(AgentRunId runId) {
        CopyOnWriteArraySet<Runnable> runListeners = listeners.get(runId);
        if (runListeners == null) return;
        for (Runnable listener : runListeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // Wake-up delivery is observational. Durable replay remains authoritative.
            }
        }
    }

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
