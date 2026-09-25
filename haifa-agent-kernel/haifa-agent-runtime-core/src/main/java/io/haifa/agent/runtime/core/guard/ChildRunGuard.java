package io.haifa.agent.runtime.core.guard;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.decision.DelegationDecision;
import io.haifa.agent.runtime.core.delegation.DelegationTool;
import java.util.Objects;

/**
 * Structural defense in depth for delegation decisions.
 *
 * <p>The delegation Tool is not disclosed to runs that may not delegate, so reaching this guard with a
 * delegation from such a run is a protocol violation. Budget limits are converged by the loop, and an
 * unknown or disallowed child agent is a repairable Tool rejection handled by the decision executor.
 */
public final class ChildRunGuard {
    public void check(AgentRun run, DelegationDecision decision) {
        Objects.requireNonNull(run, "run must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        if (run.depth() >= DelegationTool.MAX_DELEGATION_DEPTH
                || run.depth() >= run.limits().maxDepth()) {
            throw new IllegalStateException("delegation would exceed child depth limit");
        }
    }
}
