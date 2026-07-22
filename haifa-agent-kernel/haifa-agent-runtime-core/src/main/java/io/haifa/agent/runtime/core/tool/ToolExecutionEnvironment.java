package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.tool.api.FrozenToolBinding;

public interface ToolExecutionEnvironment {
    ExecutionPermit acquire(AgentRun run, FrozenToolBinding binding);

    @FunctionalInterface
    interface ExecutionPermit extends AutoCloseable {
        @Override
        void close();
    }

    static ToolExecutionEnvironment local() {
        return (run, definition) -> () -> {};
    }
}
