package io.haifa.agent.orchestration.core;

import io.haifa.agent.orchestration.api.WorkflowErrorCode;
import io.haifa.agent.orchestration.api.WorkflowException;
import io.haifa.agent.orchestration.api.WorkflowNodeId;
import io.haifa.agent.orchestration.api.WorkflowState;
import io.haifa.agent.orchestration.api.WorkflowStateDelta;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministically merges fixed branch outputs and rejects conflicting writes. */
public final class DeterministicStateMerger {
    public WorkflowState merge(WorkflowState base, List<BranchDelta> branches) {
        Objects.requireNonNull(base, "base must not be null");
        List<BranchDelta> sorted = new ArrayList<>(Objects.requireNonNull(branches, "branches must not be null"));
        sorted.sort(Comparator.comparingInt(BranchDelta::ordinal).thenComparing(BranchDelta::nodeId));
        Map<String, Object> merged = new LinkedHashMap<>();
        sorted.forEach(branch -> branch.delta().values().forEach((key, value) -> {
            Object previous = merged.putIfAbsent(key, value);
            if (previous != null && !previous.equals(value)) {
                throw new WorkflowException(
                        WorkflowErrorCode.STATE_MERGE_CONFLICT,
                        "merge",
                        "parallel branches wrote different values to state key " + key);
            }
        }));
        return base.apply(new WorkflowStateDelta(merged));
    }

    public record BranchDelta(int ordinal, WorkflowNodeId nodeId, WorkflowStateDelta delta) {
        public BranchDelta {
            if (ordinal < 0) {
                throw new IllegalArgumentException("ordinal must not be negative");
            }
            Objects.requireNonNull(nodeId, "nodeId must not be null");
            Objects.requireNonNull(delta, "delta must not be null");
        }
    }
}
