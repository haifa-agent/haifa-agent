package io.haifa.agent.runtime.core.delegation;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunResult;
import io.haifa.agent.runtime.core.decision.DelegationDecision;

/** Synchronous Agent-as-Tool delegation boundary for phase one. */
@FunctionalInterface
public interface DelegationPort {
    AgentRunResult executeChild(AgentRun parent, DelegationDecision decision);

    default boolean hasPendingChildren(AgentRun parent) {
        return false;
    }

    default void terminateChildren(AgentRun parent) {}
}
