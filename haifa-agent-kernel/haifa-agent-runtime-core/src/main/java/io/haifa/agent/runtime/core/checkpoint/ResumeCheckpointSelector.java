package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.core.checkpoint.CheckpointId;
import io.haifa.agent.core.run.AgentRunId;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** One-shot selection used to transfer a validated resume cursor into the next Attempt. */
public final class ResumeCheckpointSelector {
    private final ConcurrentHashMap<AgentRunId, CheckpointId> selections = new ConcurrentHashMap<>();

    public void select(AgentRunId runId, CheckpointId checkpointId) {
        selections.put(runId, checkpointId);
    }

    public Optional<CheckpointId> consume(AgentRunId runId) {
        return Optional.ofNullable(selections.remove(runId));
    }
}
