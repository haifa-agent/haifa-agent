package io.haifa.agent.orchestration.core.spi;

import io.haifa.agent.orchestration.api.WorkflowState;

@FunctionalInterface
public interface WorkflowConditionEvaluator {
    boolean evaluate(String conditionId, WorkflowState state);
}
