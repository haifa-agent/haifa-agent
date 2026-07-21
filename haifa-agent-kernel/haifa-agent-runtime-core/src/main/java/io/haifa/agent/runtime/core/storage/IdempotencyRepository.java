package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import java.util.Optional;

public interface IdempotencyRepository {
    Optional<AgentRunId> findRun(String callerScope, String operation, String key);

    AgentRunId recordRun(String callerScope, String operation, String key, AgentRunId runId);

    boolean markCommandApplied(String callerScope, String key);

    Optional<RuntimeCommandResult> findCommandResult(String callerScope, String idempotencyKey);

    void recordCommandResult(String callerScope, String idempotencyKey, RuntimeCommandResult result);
}
