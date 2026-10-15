package io.haifa.agent.sdk.plan;

import java.util.List;

/**
 * Input payload for the model-driven plan authoring tool.
 *
 * @param todos The list of tasks replacing the current plan.
 */
public record PlanAuthoringInput(List<PlanTodoItemInput> todos) {}
