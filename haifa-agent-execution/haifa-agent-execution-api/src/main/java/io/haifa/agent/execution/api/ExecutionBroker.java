package io.haifa.agent.execution.api;

import java.util.Optional;

public interface ExecutionBroker {
    ExecutionResult execute(ExecutionRequest request);

    default ManagedProcessSession openManagedSession(ManagedProcessRequest request) {
        throw new UnsupportedOperationException("managed process sessions are not supported");
    }

    boolean cancel(ExecutionId id);

    Optional<ExecutionResult> find(ExecutionId id);
}
