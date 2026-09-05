package io.haifa.agent.orchestration.core.spi;

import io.haifa.agent.orchestration.api.WorkflowNodeId;
import io.haifa.agent.orchestration.api.WorkflowState;
import io.haifa.agent.orchestration.api.WorkflowStateDelta;
import java.util.List;
import java.util.Objects;

/** Provider-neutral continuation for deterministic fan-out and fixed ANY_OF forks. */
public record WorkflowForkState(
        WorkflowNodeId forkNode,
        WorkflowState baseState,
        List<WorkflowNodeId> branchEntries,
        int branchIndex,
        WorkflowNodeId cursor,
        List<CompletedBranch> completedBranches,
        Mode mode) {
    public WorkflowForkState(
            WorkflowNodeId forkNode,
            WorkflowState baseState,
            List<WorkflowNodeId> branchEntries,
            int branchIndex,
            WorkflowNodeId cursor,
            List<CompletedBranch> completedBranches) {
        this(forkNode, baseState, branchEntries, branchIndex, cursor, completedBranches, Mode.ALL_OF);
    }

    public WorkflowForkState {
        Objects.requireNonNull(forkNode, "forkNode must not be null");
        Objects.requireNonNull(baseState, "baseState must not be null");
        branchEntries = List.copyOf(Objects.requireNonNull(branchEntries, "branchEntries must not be null"));
        if (branchEntries.isEmpty() || branchIndex < 0 || branchIndex >= branchEntries.size()) {
            throw new IllegalArgumentException("branchIndex must select a persisted branch");
        }
        Objects.requireNonNull(cursor, "cursor must not be null");
        completedBranches =
                List.copyOf(Objects.requireNonNull(completedBranches, "completedBranches must not be null"));
        Objects.requireNonNull(mode, "mode must not be null");
    }

    public enum Mode {
        ALL_OF,
        ANY_OF
    }

    public record CompletedBranch(int ordinal, WorkflowNodeId entryNode, WorkflowStateDelta delta) {
        public CompletedBranch {
            if (ordinal < 0) throw new IllegalArgumentException("ordinal must be non-negative");
            Objects.requireNonNull(entryNode, "entryNode must not be null");
            Objects.requireNonNull(delta, "delta must not be null");
        }
    }
}
