package io.haifa.agent.runtime.core.trace;

@FunctionalInterface
public interface TracePort {
    void record(RuntimeTraceEvent event);

    static TracePort noop() {
        return event -> {};
    }
}
