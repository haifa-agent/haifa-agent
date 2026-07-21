package io.haifa.agent.runtime.core.guard;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.decision.DelegationDecision;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.Objects;

/** Enforces both hierarchy depth and aggregate child-run budget before delegation. */
public final class ChildRunGuard {
    private final RuntimeStateRepository state;

    public ChildRunGuard(RuntimeStateRepository state) {
        this.state = Objects.requireNonNull(state);
    }

    public void check(AgentRun run, DelegationDecision decision) {
        if (decision.childDefinitionId().equals(run.agentDefinitionId())) {
            throw new IllegalStateException("an agent cannot recursively delegate to itself");
        }
        if (run.depth() >= run.limits().maxDepth()) {
            throw new IllegalStateException("delegation would exceed child depth limit");
        }
        if (run.usage().childRuns() >= run.budget().maxChildRuns()) {
            throw new IllegalStateException("delegation would exceed child run budget");
        }
        var configuration = state.configuration(run.configurationSnapshot())
                .orElseThrow(() -> new IllegalStateException("run configuration snapshot is unavailable"));
        if (!configuration.allowedChildAgents().contains(decision.childDefinitionId())) {
            throw new SecurityException("child agent type is not allowed by the frozen configuration");
        }
    }
}
