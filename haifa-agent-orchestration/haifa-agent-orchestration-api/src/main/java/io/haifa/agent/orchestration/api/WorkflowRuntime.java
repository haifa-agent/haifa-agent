package io.haifa.agent.orchestration.api;

import java.util.List;
import java.util.Optional;

public interface WorkflowRuntime {
    WorkflowRunSnapshot start(WorkflowStartRequest request);

    Optional<WorkflowRunSnapshot> find(WorkflowRunId runId);

    WorkflowRunSnapshot resume(WorkflowResumeRequest request);

    WorkflowRunSnapshot cancel(WorkflowCancelRequest request);

    WorkflowRunSnapshot timeout(WorkflowTimeoutRequest request);

    List<WorkflowEvent> events(WorkflowRunId runId, long afterSequence, int limit);
}
