package io.haifa.agent.runtime.core.decision;

import io.haifa.agent.core.run.AgentRun;

@FunctionalInterface
public interface DecisionValidator {
    void validate(AgentRun run, AgentDecision decision);
}
