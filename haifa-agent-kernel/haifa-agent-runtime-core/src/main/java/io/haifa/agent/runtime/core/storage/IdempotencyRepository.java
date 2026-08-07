package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import java.util.Optional;

public interface IdempotencyRepository {
    Optional<RunStartIdempotencyBinding> findRunBinding(String callerScope, String operation, String key);

    RunStartIdempotencyBinding recordRunBinding(RunStartIdempotencyBinding binding);

    default Optional<AgentRunId> findRun(String callerScope, String operation, String key) {
        return findRunBinding(callerScope, operation, key).map(RunStartIdempotencyBinding::runId);
    }

    default AgentRunId recordRun(String callerScope, String operation, String key, AgentRunId runId) {
        return recordRunBinding(new RunStartIdempotencyBinding(callerScope, operation, key, Optional.empty(), runId))
                .runId();
    }

    boolean markCommandApplied(String callerScope, String key);

    Optional<RuntimeCommandResult> findCommandResult(String callerScope, String idempotencyKey);

    void recordCommandResult(String callerScope, String idempotencyKey, RuntimeCommandResult result);
}
