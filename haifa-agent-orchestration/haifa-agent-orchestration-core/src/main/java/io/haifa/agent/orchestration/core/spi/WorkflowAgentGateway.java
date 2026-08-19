package io.haifa.agent.orchestration.core.spi;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.orchestration.api.WorkflowNodeDefinition;
import io.haifa.agent.orchestration.api.WorkflowRunId;
import io.haifa.agent.orchestration.api.WorkflowState;
import io.haifa.agent.orchestration.api.WorkflowStateDelta;
import java.util.Objects;

@FunctionalInterface
public interface WorkflowAgentGateway {
    AgentExecution execute(WorkflowRunId workflowRunId, WorkflowNodeDefinition node, WorkflowState state);

    record AgentExecution(AgentRunId agentRunId, WorkflowStateDelta delta) {
        public AgentExecution {
            Objects.requireNonNull(agentRunId, "agentRunId must not be null");
            Objects.requireNonNull(delta, "delta must not be null");
        }
    }
}
