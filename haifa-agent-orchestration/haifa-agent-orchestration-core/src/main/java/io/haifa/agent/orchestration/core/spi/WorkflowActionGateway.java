package io.haifa.agent.orchestration.core.spi;

import io.haifa.agent.orchestration.api.WorkflowNodeDefinition;
import io.haifa.agent.orchestration.api.WorkflowRunId;
import io.haifa.agent.orchestration.api.WorkflowState;
import io.haifa.agent.orchestration.api.WorkflowStateDelta;

@FunctionalInterface
public interface WorkflowActionGateway {
    WorkflowStateDelta execute(WorkflowRunId runId, WorkflowNodeDefinition node, WorkflowState state);
}
