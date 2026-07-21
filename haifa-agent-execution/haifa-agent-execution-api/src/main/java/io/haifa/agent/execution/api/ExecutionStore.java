package io.haifa.agent.execution.api;

import java.util.Optional;

public interface ExecutionStore {
    void create(ExecutionRequest request);

    void complete(ExecutionRequest request, ExecutionResult result);

    Optional<ExecutionRequest> findRequest(ExecutionId id);

    Optional<ExecutionResult> findResult(ExecutionId id);

    Optional<ExecutionResult> findByIdempotencyKey(String key);
}
