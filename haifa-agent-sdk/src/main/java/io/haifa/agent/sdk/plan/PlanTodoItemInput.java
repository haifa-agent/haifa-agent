package io.haifa.agent.sdk.plan;

/**
 * Single task item in a plan authoring request.
 *
 * @param content The title or content of the task item.
 * @param status The status of the task item (pending, in_progress, completed).
 */
public record PlanTodoItemInput(String content, String status) {}
