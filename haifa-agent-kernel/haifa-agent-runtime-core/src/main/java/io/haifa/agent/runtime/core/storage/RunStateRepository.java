package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import java.util.List;
import java.util.Optional;

public interface RunStateRepository {
    void insert(AgentRun run);

    void save(AgentRun run, long expectedVersion);

    Optional<AgentRun> find(AgentRunId runId);

    /** Direct child runs of {@code parentRunId}, oldest first; the parent-child relation is the run row itself. */
    List<AgentRun> children(AgentRunId parentRunId);
}
