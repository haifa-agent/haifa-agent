package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;

@FunctionalInterface
public interface AgentLoop {
    AgentLoopResult run(AgentRun run, AgentRunExecutionAttempt attempt);
}
