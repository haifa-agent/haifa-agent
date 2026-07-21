package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.run.AgentRun;

public interface ToolExecutionEnvironment {
    ExecutionPermit acquire(AgentRun run, ToolDefinition definition);

    @FunctionalInterface
    interface ExecutionPermit extends AutoCloseable {
        @Override
        void close();
    }

    static ToolExecutionEnvironment local() {
        return (run, definition) -> () -> {};
    }
}
