package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.trace.RuntimeTraceContext;

@FunctionalInterface
public interface AgentLoop {
    AgentLoopResult run(AgentRun run, AgentRunExecutionAttempt attempt, RuntimeTraceContext traceContext);
}
