package io.haifa.agent.application.coding.terminal.application;

/** UI-owned selection generations. A pending switch leaves the displayed selection active until installation. */
final class TerminalSelection {
    private long next;
    private long current;
    private long pending;

    long begin() {
        pending = ++next;
        return pending;
    }

    long current() {
        return current;
    }

    long pending() {
        return pending;
    }

    boolean isPending(long generation) {
        return pending != 0 && pending == generation;
    }

    boolean acceptsLoad(long generation) {
        return generation == current || isPending(generation);
    }

    boolean abandon(long generation) {
        if (pending != generation) return false;
        pending = 0;
        return true;
    }

    void install(long generation) {
        current = generation;
        abandon(generation);
    }

    void clear() {
        current = 0;
        pending = 0;
    }
}
