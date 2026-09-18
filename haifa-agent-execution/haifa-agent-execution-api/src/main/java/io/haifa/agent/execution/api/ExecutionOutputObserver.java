package io.haifa.agent.execution.api;

@FunctionalInterface
public interface ExecutionOutputObserver {
    /** Called only after the operating-system process has been created successfully. */
    default void onStarted() {}

    /** Called with a host-local process identity when the provider can expose one safely. */
    default void onStarted(ExecutionProcessIdentity identity) {
        onStarted();
    }

    void onOutput(ProcessOutputChunk chunk);

    static ExecutionOutputObserver noop() {
        return ignored -> {};
    }
}
