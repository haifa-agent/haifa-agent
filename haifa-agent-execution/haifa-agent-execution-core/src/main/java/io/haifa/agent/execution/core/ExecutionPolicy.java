package io.haifa.agent.execution.core;

import io.haifa.agent.execution.api.ExecutionRequest;

@FunctionalInterface
public interface ExecutionPolicy {
    void authorize(ExecutionRequest request);
}
