package io.haifa.agent.runtime.core.guard;

import io.haifa.agent.core.run.AgentRun;

/** Frozen limits that decide whether a Run may start the model call of a given loop iteration. */
public final class ModelContinuationLimits {
    private ModelContinuationLimits() {}

    /**
     * Returns the typed limit signal that forbids another model call in {@code iteration}, or {@code null} when the
     * Run may still call the model.
     */
    public static RuntimeException exceeded(AgentRun run, int iteration) {
        if (run.usage().modelCalls() >= run.limits().maxModelCalls()) {
            return new RuntimeLimitExceededException(
                    "modelCalls", run.limits().maxModelCalls(), run.usage().modelCalls());
        }
        if (iteration > run.limits().maxIterations()) {
            return new RuntimeLimitExceededException(
                    "iterations", run.limits().maxIterations(), Math.max(0, iteration - 1L));
        }
        var quota = run.quotaPolicy();
        if (quota.mode() == io.haifa.agent.core.run.QuotaMode.HARD_STOP) {
            if (quota.maxInputTokens() != null
                    && quota.maxInputTokens() > 0
                    && run.usage().inputTokens() >= quota.maxInputTokens()) {
                return new RuntimeQuotaExceededException(
                        "inputTokens", quota.maxInputTokens(), run.usage().inputTokens());
            }
            if (quota.maxOutputTokens() != null
                    && quota.maxOutputTokens() > 0
                    && run.usage().outputTokens() >= quota.maxOutputTokens()) {
                return new RuntimeQuotaExceededException(
                        "outputTokens", quota.maxOutputTokens(), run.usage().outputTokens());
            }
            if (quota.maxCostMinorUnits() != null
                    && quota.maxCostMinorUnits() > 0
                    && run.usage().costMinorUnits() >= quota.maxCostMinorUnits()) {
                return new RuntimeQuotaExceededException(
                        "costMinorUnits", quota.maxCostMinorUnits(), run.usage().costMinorUnits());
            }
        }
        return null;
    }
}
