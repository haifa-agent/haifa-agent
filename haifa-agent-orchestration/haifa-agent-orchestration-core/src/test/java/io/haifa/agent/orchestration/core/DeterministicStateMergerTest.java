package io.haifa.agent.orchestration.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.orchestration.api.WorkflowErrorCode;
import io.haifa.agent.orchestration.api.WorkflowException;
import io.haifa.agent.orchestration.api.WorkflowNodeId;
import io.haifa.agent.orchestration.api.WorkflowState;
import io.haifa.agent.orchestration.api.WorkflowStateDelta;
import io.haifa.agent.orchestration.api.WorkflowStateSchema;
import io.haifa.agent.orchestration.core.DeterministicStateMerger.BranchDelta;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeterministicStateMergerTest {
    private static final WorkflowStateSchema SCHEMA =
            new WorkflowStateSchema("merge", 1, Set.of("shared", "left", "right"), 8, 4, 128);
    private final DeterministicStateMerger merger = new DeterministicStateMerger();

    @Test
    void branchCompletionOrderDoesNotChangeMergedState() {
        WorkflowState base = new WorkflowState(SCHEMA, Map.of("shared", "base"));
        BranchDelta left = new BranchDelta(1, new WorkflowNodeId("left"), new WorkflowStateDelta(Map.of("left", "L")));
        BranchDelta right =
                new BranchDelta(2, new WorkflowNodeId("right"), new WorkflowStateDelta(Map.of("right", "R")));

        assertThat(merger.merge(base, List.of(left, right))).isEqualTo(merger.merge(base, List.of(right, left)));
    }

    @Test
    void conflictingBranchWritesFailClosed() {
        WorkflowState base = new WorkflowState(SCHEMA, Map.of());
        List<BranchDelta> branches = List.of(
                new BranchDelta(1, new WorkflowNodeId("left"), new WorkflowStateDelta(Map.of("shared", "L"))),
                new BranchDelta(2, new WorkflowNodeId("right"), new WorkflowStateDelta(Map.of("shared", "R"))));

        assertThatThrownBy(() -> merger.merge(base, branches))
                .isInstanceOfSatisfying(WorkflowException.class, exception -> assertThat(exception.code())
                        .isEqualTo(WorkflowErrorCode.STATE_MERGE_CONFLICT));
    }
}
