package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.checkpoint.Checkpoint;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.checkpoint.RuntimeCheckpointState;
import java.util.List;
import java.util.Optional;

public interface CheckpointRepository {
    void append(Checkpoint checkpoint, RuntimeCheckpointState state);

    Optional<Checkpoint> latest(AgentRunId runId);

    Optional<RuntimeCheckpointState> state(String checkpointId);

    List<Checkpoint> checkpointsFor(AgentRunId runId);
}
