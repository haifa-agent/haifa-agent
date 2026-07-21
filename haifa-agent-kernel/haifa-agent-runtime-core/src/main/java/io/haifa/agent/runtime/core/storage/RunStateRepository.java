package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import java.util.Optional;

public interface RunStateRepository {
    void insert(AgentRun run);

    void save(AgentRun run, long expectedVersion);

    Optional<AgentRun> find(AgentRunId runId);
}
