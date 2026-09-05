package io.haifa.agent.orchestration.core.spi;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.orchestration.api.WorkflowNodeDefinition;
import io.haifa.agent.orchestration.api.WorkflowRunId;
import io.haifa.agent.orchestration.api.WorkflowState;
import io.haifa.agent.orchestration.api.WorkflowStateDelta;
import java.util.Objects;
import java.util.Optional;

@FunctionalInterface
public interface WorkflowAgentGateway {
    AgentExecution execute(WorkflowRunId workflowRunId, WorkflowNodeDefinition node, WorkflowState state);

    /** Returns a proven terminal result for a previously linked Agent Run, if available. */
    default Optional<AgentExecution> recover(
            WorkflowRunId workflowRunId, WorkflowNodeDefinition node, WorkflowState state, AgentRunId agentRunId) {
        return Optional.empty();
    }

    /** Propagates cancellation only after a durable Agent Run association exists. */
    default void cancel(AgentRunId agentRunId) {}

    record AgentExecution(AgentRunId agentRunId, WorkflowStateDelta delta) {
        public AgentExecution {
            Objects.requireNonNull(agentRunId, "agentRunId must not be null");
            Objects.requireNonNull(delta, "delta must not be null");
        }
    }
}
