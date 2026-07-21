package io.haifa.agent.runtime.core.completion;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;

@FunctionalInterface
public interface RequiredArtifactChecker {
    boolean isSatisfied(AgentRun run, FinalAnswerDecision decision);
}
