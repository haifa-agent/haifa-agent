package io.haifa.agent.execution.api;

@FunctionalInterface
public interface ExecutionOutputObserver {
    /** Called only after the operating-system process has been created successfully. */
    default void onStarted() {}

    void onOutput(ProcessOutputChunk chunk);

    static ExecutionOutputObserver noop() {
        return ignored -> {};
    }
}
