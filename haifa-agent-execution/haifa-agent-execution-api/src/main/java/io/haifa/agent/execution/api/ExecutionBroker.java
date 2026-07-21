package io.haifa.agent.execution.api;

import java.util.Optional;

public interface ExecutionBroker {
    ExecutionResult execute(ExecutionRequest request);

    boolean cancel(ExecutionId id);

    Optional<ExecutionResult> find(ExecutionId id);
}
