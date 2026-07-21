package io.haifa.agent.runtime.core.completion;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;

@FunctionalInterface
public interface OutputContractValidator {
    boolean isValid(AgentRun run, FinalAnswerDecision decision);
}
