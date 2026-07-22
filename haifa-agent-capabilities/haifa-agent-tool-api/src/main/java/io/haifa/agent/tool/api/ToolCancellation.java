package io.haifa.agent.tool.api;

@FunctionalInterface
public interface ToolCancellation {
    boolean isCancellationRequested();

    default void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new ToolInvocationException("tool invocation was cancelled");
        }
    }
}
