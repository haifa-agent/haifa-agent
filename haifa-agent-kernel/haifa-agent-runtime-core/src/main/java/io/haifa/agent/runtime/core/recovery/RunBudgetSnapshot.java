package io.haifa.agent.runtime.core.recovery;

import io.haifa.agent.core.run.AgentRun;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure remaining-budget view computed from frozen limits, authoritative usage and an injected time. */
public record RunBudgetSnapshot(
        long remainingModelCalls,
        long remainingToolCalls,
        long remainingIterations,
        long remainingWallTimeMillis,
        long remainingInputTokens,
        long remainingOutputTokens,
        int failureClusterAttempts,
        int completionRepairAttempts,
        String limitingResource,
        long limitingUsed,
        long limitingLimit,
        int remainingPercent) {
    public static RunBudgetSnapshot from(
            AgentRun run, int iteration, int failureClusterAttempts, int completionRepairAttempts, Instant now) {
        long model = remaining(run.limits().maxModelCalls(), run.usage().modelCalls());
        long tools = remaining(run.limits().maxToolCalls(), run.usage().toolCalls());
        long iterations = Math.max(0, (long) run.limits().maxIterations() - iteration + 1L);
        long activeElapsedMillis = run.activeElapsedMillis(now);
        long wall = Math.max(0, run.limits().maxWallTimeMillis() - activeElapsedMillis);

        var quota = run.quotaPolicy();
        long maxInput = quota.maxInputTokens() != null
                ? quota.maxInputTokens()
                : run.budget().maxInputTokens();
        long maxOutput = quota.maxOutputTokens() != null
                ? quota.maxOutputTokens()
                : run.budget().maxOutputTokens();
        long maxCachedInput = quota.maxCachedInputTokens() != null
                ? quota.maxCachedInputTokens()
                : run.budget().maxCachedInputTokens();
        long maxCost = quota.maxCostMinorUnits() != null
                ? quota.maxCostMinorUnits()
                : run.budget().maxCostMinorUnits();

        long input = maxInput > 0 ? remaining(maxInput, run.usage().inputTokens()) : Long.MAX_VALUE;
        long output = maxOutput > 0 ? remaining(maxOutput, run.usage().outputTokens()) : Long.MAX_VALUE;
        long cachedInput =
                maxCachedInput > 0 ? remaining(maxCachedInput, run.usage().cachedInputTokens()) : Long.MAX_VALUE;
        long children = remaining(run.limits().maxChildRuns(), run.usage().childRuns());
        long cost = maxCost > 0 ? remaining(maxCost, run.usage().costMinorUnits()) : Long.MAX_VALUE;

        List<BudgetDimension> dimensions = new ArrayList<>();
        dimensions.add(new BudgetDimension(
                "MODEL_CALLS",
                run.usage().modelCalls(),
                run.limits().maxModelCalls(),
                percent(model, run.limits().maxModelCalls())));
        dimensions.add(new BudgetDimension(
                "TOOL_CALLS",
                run.usage().toolCalls(),
                run.limits().maxToolCalls(),
                percent(tools, run.limits().maxToolCalls())));
        dimensions.add(new BudgetDimension(
                "ITERATIONS",
                Math.max(0, iteration - 1L),
                run.limits().maxIterations(),
                percent(iterations, run.limits().maxIterations())));
        dimensions.add(new BudgetDimension(
                "WALL_TIME_MILLIS",
                activeElapsedMillis,
                run.limits().maxWallTimeMillis(),
                percent(wall, run.limits().maxWallTimeMillis())));
        if (maxInput > 0) {
            dimensions.add(
                    new BudgetDimension("INPUT_TOKENS", run.usage().inputTokens(), maxInput, percent(input, maxInput)));
        }
        if (maxOutput > 0) {
            dimensions.add(new BudgetDimension(
                    "OUTPUT_TOKENS", run.usage().outputTokens(), maxOutput, percent(output, maxOutput)));
        }
        if (maxCachedInput > 0) {
            dimensions.add(new BudgetDimension(
                    "CACHED_INPUT_TOKENS",
                    run.usage().cachedInputTokens(),
                    maxCachedInput,
                    percent(cachedInput, maxCachedInput)));
        }
        dimensions.add(new BudgetDimension(
                "CHILD_RUNS",
                run.usage().childRuns(),
                run.limits().maxChildRuns(),
                percent(children, run.limits().maxChildRuns())));
        if (maxCost > 0) {
            dimensions.add(new BudgetDimension(
                    "COST_MINOR_UNITS", run.usage().costMinorUnits(), maxCost, percent(cost, maxCost)));
        }

        BudgetDimension limiting = minimum(dimensions);
        return new RunBudgetSnapshot(
                model,
                tools,
                iterations,
                wall,
                input,
                output,
                Math.max(0, failureClusterAttempts),
                Math.max(0, completionRepairAttempts),
                limiting.resource(),
                limiting.used(),
                limiting.limit(),
                limiting.remainingPercent());
    }

    public Set<Integer> crossedThresholds() {
        LinkedHashSet<Integer> thresholds = new LinkedHashSet<>();
        for (int threshold : new int[] {50, 25, 10}) {
            if (remainingPercent <= threshold) thresholds.add(threshold);
        }
        return Set.copyOf(thresholds);
    }

    public String promptText() {
        return ("Remaining resource budget: modelCalls=%d, toolCalls=%d, iterations=%d, wallTimeMillis=%d, "
                        + "inputTokens=%d, outputTokens=%d, failureClusterAttempts=%d, completionRepairAttempts=%d.")
                .formatted(
                        remainingModelCalls,
                        remainingToolCalls,
                        remainingIterations,
                        remainingWallTimeMillis,
                        remainingInputTokens,
                        remainingOutputTokens,
                        failureClusterAttempts,
                        completionRepairAttempts);
    }

    private static long remaining(long maximum, long used) {
        return Math.max(0, maximum - used);
    }

    private static int percent(long remaining, long maximum) {
        if (maximum <= 0) return 100;
        return (int) Math.max(0, Math.min(100, remaining * 100L / maximum));
    }

    private static BudgetDimension minimum(List<BudgetDimension> values) {
        BudgetDimension result = values.get(0);
        for (BudgetDimension value : values) {
            if (value.remainingPercent() < result.remainingPercent()) result = value;
        }
        return result;
    }

    private record BudgetDimension(String resource, long used, long limit, int remainingPercent) {}
}
