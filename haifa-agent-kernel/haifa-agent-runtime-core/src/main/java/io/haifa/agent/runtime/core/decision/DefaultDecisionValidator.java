package io.haifa.agent.runtime.core.decision;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.guard.ChildRunGuard;
import io.haifa.agent.runtime.core.guard.DuplicateToolCallGuard;
import java.util.Objects;

public final class DefaultDecisionValidator implements DecisionValidator {
    private final DuplicateToolCallGuard duplicateToolCalls;
    private final ChildRunGuard childRuns;

    public DefaultDecisionValidator(DuplicateToolCallGuard duplicateToolCalls, ChildRunGuard childRuns) {
        this.duplicateToolCalls = Objects.requireNonNull(duplicateToolCalls);
        this.childRuns = Objects.requireNonNull(childRuns);
    }

    @Override
    public void validate(AgentRun run, AgentDecision decision) {
        Objects.requireNonNull(run, "run must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        if (decision instanceof ToolCallDecision tools) {
            duplicateToolCalls.check(run, tools);
        }
        if (decision instanceof DelegationDecision delegation) {
            childRuns.check(run, delegation);
        }
    }
}
