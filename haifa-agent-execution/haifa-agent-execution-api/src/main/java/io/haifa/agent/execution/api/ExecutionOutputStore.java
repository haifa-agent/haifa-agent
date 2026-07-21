package io.haifa.agent.execution.api;

public interface ExecutionOutputStore {
    ExecutionOutput store(
            ExecutionId id, ExecutionOutputChannel channel, byte[] bytes, int inlineSummaryBytes, boolean truncated);
}
