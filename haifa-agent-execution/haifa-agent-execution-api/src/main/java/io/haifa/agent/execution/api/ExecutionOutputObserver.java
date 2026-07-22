package io.haifa.agent.execution.api;

@FunctionalInterface
public interface ExecutionOutputObserver {
    void onOutput(ProcessOutputChunk chunk);

    static ExecutionOutputObserver noop() {
        return ignored -> {};
    }
}
