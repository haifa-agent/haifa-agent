package io.haifa.agent.runtime.core.control;

public enum RunControlSignal {
    NONE(0),
    PAUSE(10),
    TIMEOUT(60),
    LEASE_LOST(70),
    ADMIN_STOP(80),
    PARENT_CANCELLED(90),
    CANCEL(100);

    private final int priority;

    RunControlSignal(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}
