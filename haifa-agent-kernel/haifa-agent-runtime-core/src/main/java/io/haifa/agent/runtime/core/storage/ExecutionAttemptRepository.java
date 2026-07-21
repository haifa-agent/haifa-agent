package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptId;
import java.util.List;
import java.util.Optional;

public interface ExecutionAttemptRepository {
    void insert(AgentRunExecutionAttempt attempt);

    void save(AgentRunExecutionAttempt attempt, long expectedVersion);

    Optional<AgentRunExecutionAttempt> find(ExecutionAttemptId id);

    Optional<AgentRunExecutionAttempt> activeFor(AgentRunId runId);

    List<AgentRunExecutionAttempt> attemptsFor(AgentRunId runId);
}
