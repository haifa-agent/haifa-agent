package io.haifa.agent.orchestration.core.spi;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.orchestration.api.WorkflowRunSnapshot;
import io.haifa.agent.orchestration.api.WorkflowStateDelta;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Complete provider-neutral control state required to resume a workflow run. */
public record StoredWorkflowRun(
        WorkflowRunSnapshot snapshot,
        WorkflowPersistenceBinding binding,
        long storageVersion,
        long eventSequence,
        Map<String, Integer> nodeVisits,
        Set<String> consumedSignalIds,
        Optional<WorkflowStateDelta> pendingDelta,
        Optional<WorkflowForkState> forkState,
        Optional<AgentRunId> pendingAgentCancellation) {
    public StoredWorkflowRun {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(binding, "binding must not be null");
        if (storageVersion < 1 || eventSequence < 0) {
            throw new IllegalArgumentException("storageVersion must be positive and eventSequence non-negative");
        }
        nodeVisits = Map.copyOf(Objects.requireNonNull(nodeVisits, "nodeVisits must not be null"));
        if (nodeVisits.entrySet().stream().anyMatch(entry -> entry.getKey().isBlank() || entry.getValue() < 1)) {
            throw new IllegalArgumentException("nodeVisits must contain valid node ids and positive counts");
        }
        consumedSignalIds = Set.copyOf(Objects.requireNonNull(consumedSignalIds, "consumedSignalIds must not be null"));
        pendingDelta = Objects.requireNonNull(pendingDelta, "pendingDelta must not be null");
        forkState = Objects.requireNonNull(forkState, "forkState must not be null");
        pendingAgentCancellation =
                Objects.requireNonNull(pendingAgentCancellation, "pendingAgentCancellation must not be null");
    }
}
