package io.haifa.agent.runtime.core.completion;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;

@FunctionalInterface
public interface CompletionGuard {
    CompletionReadiness evaluate(AgentRun run, FinalAnswerDecision decision);
}
