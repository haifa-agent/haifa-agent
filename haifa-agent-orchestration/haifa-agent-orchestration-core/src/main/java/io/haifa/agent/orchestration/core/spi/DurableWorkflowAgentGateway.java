package io.haifa.agent.orchestration.core.spi;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.orchestration.api.WorkflowNodeDefinition;
import io.haifa.agent.orchestration.api.WorkflowRunId;
import io.haifa.agent.orchestration.api.WorkflowState;

/**
 * Split Agent gateway required by the durable coordinator.
 *
 * <p>{@link #start} runs inside the Workflow UoW so the authoritative Agent Run creation and Node
 * Attempt association commit or roll back together. Awaiting terminal work happens after commit.
 */
public interface DurableWorkflowAgentGateway extends WorkflowAgentGateway {
    AgentRunId start(WorkflowRunId workflowRunId, WorkflowNodeDefinition node, WorkflowState state);

    AgentExecution await(
            WorkflowRunId workflowRunId, WorkflowNodeDefinition node, WorkflowState state, AgentRunId agentRunId);

    @Override
    default AgentExecution execute(WorkflowRunId workflowRunId, WorkflowNodeDefinition node, WorkflowState state) {
        AgentRunId agentRunId = start(workflowRunId, node, state);
        return await(workflowRunId, node, state, agentRunId);
    }
}
