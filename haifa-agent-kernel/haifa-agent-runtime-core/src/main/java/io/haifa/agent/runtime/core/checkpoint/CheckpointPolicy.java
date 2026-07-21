package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.core.checkpoint.CheckpointType;
import io.haifa.agent.core.run.AgentRun;

@FunctionalInterface
public interface CheckpointPolicy {
    boolean shouldCapture(AgentRun run, int completedIteration, CheckpointType reason);

    static CheckpointPolicy everyIteration() {
        return (run, iteration, reason) -> true;
    }
}
