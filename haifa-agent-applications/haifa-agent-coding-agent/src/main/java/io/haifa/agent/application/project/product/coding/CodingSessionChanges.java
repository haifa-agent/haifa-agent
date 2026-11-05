package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.session.AgentSessionId;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Process-local product invalidations, independent of HTTP receipts and Run-scoped event feeds. */
public final class CodingSessionChanges {
    private static final int MAXIMUM_OBSERVERS = 128;
    private final Map<AgentSessionId, Set<Runnable>> observers = new HashMap<>();
    private int count;

    public synchronized Registration observe(AgentSessionId sessionId, Runnable listener) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        if (count >= MAXIMUM_OBSERVERS) throw new IllegalStateException("CODING_OBSERVATION_LIMIT");
        // Each registration has independent ownership even if callbacks happen to be equal.
        Runnable owned = new Runnable() {
            @Override
            public void run() {
                listener.run();
            }
        };
        observers.computeIfAbsent(sessionId, ignored -> new LinkedHashSet<>()).add(owned);
        count++;
        return () -> remove(sessionId, owned);
    }

    synchronized boolean observed(AgentSessionId sessionId) {
        return observers.containsKey(sessionId);
    }

    void changed(AgentSessionId sessionId) {
        List<Runnable> copy;
        synchronized (this) {
            copy = List.copyOf(observers.getOrDefault(sessionId, Set.of()));
        }
        for (Runnable listener : copy) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // Observation failure cannot turn an accepted command into a rejected command.
            }
        }
    }

    private synchronized void remove(AgentSessionId sessionId, Runnable listener) {
        Set<Runnable> values = observers.get(sessionId);
        if (values == null || !values.remove(listener)) return;
        count--;
        if (values.isEmpty()) observers.remove(sessionId);
    }

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
