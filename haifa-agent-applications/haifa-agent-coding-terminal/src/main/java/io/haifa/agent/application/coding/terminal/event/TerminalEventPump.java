package io.haifa.agent.application.coding.terminal.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Bounded hand-off from Runtime callbacks to the single terminal UI thread.
 *
 * <p>A rejected action is never acknowledged by the reducer, so its event cursor remains replayable.
 */
public final class TerminalEventPump {
    private final ArrayBlockingQueue<TerminalUiAction> queue;

    public TerminalEventPump(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    public boolean offer(TerminalUiAction action) {
        return queue.offer(Objects.requireNonNull(action, "action must not be null"));
    }

    public List<TerminalUiAction> drain(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<TerminalUiAction> actions = new ArrayList<>(Math.min(limit, queue.size()));
        queue.drainTo(actions, limit);
        return List.copyOf(actions);
    }

    public int pendingCount() {
        return queue.size();
    }
}
