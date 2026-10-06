package io.haifa.agent.sdk.plan;

/**
 * Output payload returned after plan authoring tool execution.
 *
 * @param revision The resulting revision number of the plan.
 * @param itemCount The number of tasks in the plan.
 * @param message Human-readable summary of the plan update.
 */
public record PlanAuthoringOutput(long revision, int itemCount, String message) {}
