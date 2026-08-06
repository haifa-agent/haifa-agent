package io.haifa.agent.execution.api;

/** Observes the point at which an execution has started and can no longer be safely replayed. */
@FunctionalInterface
public interface ExecutionDispatchObserver {
    void dispatched();

    static ExecutionDispatchObserver noop() {
        return () -> {};
    }
}
