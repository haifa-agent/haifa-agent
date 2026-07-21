package io.haifa.agent.runtime.api;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import java.time.Duration;
import java.util.Optional;

/** Optional blocking client facade over the snapshot-first Runtime API. */
public interface AgentRunHandle {
    AgentRunId runId();

    AgentRunStatus status();

    AgentRunSnapshot snapshot();

    AgentRunSnapshot awaitCompletion() throws InterruptedException;

    Optional<AgentRunSnapshot> awaitCompletion(Duration timeout) throws InterruptedException;

    RuntimeCommandResult pause();

    RuntimeCommandResult cancel();
}
