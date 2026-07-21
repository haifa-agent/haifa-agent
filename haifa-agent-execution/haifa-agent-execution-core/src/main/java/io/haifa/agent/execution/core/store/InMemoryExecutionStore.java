package io.haifa.agent.execution.core.store;

import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStore;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryExecutionStore implements ExecutionStore {
    private final ConcurrentHashMap<ExecutionId, ExecutionRequest> requests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ExecutionId, ExecutionResult> results = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ExecutionId> idempotency = new ConcurrentHashMap<>();

    @Override
    public synchronized void create(ExecutionRequest request) {
        ExecutionId existing = idempotency.putIfAbsent(request.idempotencyKey(), request.id());
        if (existing != null || requests.putIfAbsent(request.id(), request) != null) {
            throw new IllegalStateException("execution id or idempotency key already exists");
        }
    }

    @Override
    public void complete(ExecutionRequest request, ExecutionResult result) {
        if (!requests.containsKey(request.id())) throw new IllegalStateException("execution request does not exist");
        if (results.putIfAbsent(request.id(), result) != null)
            throw new IllegalStateException("execution already completed");
    }

    @Override
    public Optional<ExecutionRequest> findRequest(ExecutionId id) {
        return Optional.ofNullable(requests.get(id));
    }

    @Override
    public Optional<ExecutionResult> findResult(ExecutionId id) {
        return Optional.ofNullable(results.get(id));
    }

    @Override
    public Optional<ExecutionResult> findByIdempotencyKey(String key) {
        return Optional.ofNullable(idempotency.get(key)).flatMap(this::findResult);
    }
}
