package io.haifa.agent.runtime.core.recovery;

import io.haifa.agent.core.run.AgentRun;
import java.time.Instant;
import java.util.LinkedHashSet;
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
        long model = remaining(run.budget().maxModelCalls(), run.usage().modelCalls());
        long tools = remaining(run.budget().maxToolCalls(), run.usage().toolCalls());
        long iterations = Math.max(0, (long) run.limits().maxIterations() - iteration + 1L);
        long activeElapsedMillis = run.activeElapsedMillis(now);
        long wall = Math.max(0, run.limits().maxWallTimeMillis() - activeElapsedMillis);
        long input = remaining(run.budget().maxInputTokens(), run.usage().inputTokens());
        long output = remaining(run.budget().maxOutputTokens(), run.usage().outputTokens());
        long cachedInput =
                remaining(run.budget().maxCachedInputTokens(), run.usage().cachedInputTokens());
        long children = remaining(run.budget().maxChildRuns(), run.usage().childRuns());
        long cost = remaining(run.budget().maxCostMinorUnits(), run.usage().costMinorUnits());
        BudgetDimension limiting = minimum(
                new BudgetDimension(
                        "MODEL_CALLS",
                        run.usage().modelCalls(),
                        run.budget().maxModelCalls(),
                        percent(model, run.budget().maxModelCalls())),
                new BudgetDimension(
                        "TOOL_CALLS",
                        run.usage().toolCalls(),
                        run.budget().maxToolCalls(),
                        percent(tools, run.budget().maxToolCalls())),
                new BudgetDimension(
                        "ITERATIONS",
                        Math.max(0, iteration - 1L),
                        run.limits().maxIterations(),
                        percent(iterations, run.limits().maxIterations())),
                new BudgetDimension(
                        "WALL_TIME_MILLIS",
                        activeElapsedMillis,
                        run.limits().maxWallTimeMillis(),
                        percent(wall, run.limits().maxWallTimeMillis())),
                new BudgetDimension(
                        "INPUT_TOKENS",
                        run.usage().inputTokens(),
                        run.budget().maxInputTokens(),
                        percent(input, run.budget().maxInputTokens())),
                new BudgetDimension(
                        "OUTPUT_TOKENS",
                        run.usage().outputTokens(),
                        run.budget().maxOutputTokens(),
                        percent(output, run.budget().maxOutputTokens())),
                new BudgetDimension(
                        "CACHED_INPUT_TOKENS",
                        run.usage().cachedInputTokens(),
                        run.budget().maxCachedInputTokens(),
                        percent(cachedInput, run.budget().maxCachedInputTokens())),
                new BudgetDimension(
                        "CHILD_RUNS",
                        run.usage().childRuns(),
                        run.budget().maxChildRuns(),
                        percent(children, run.budget().maxChildRuns())),
                new BudgetDimension(
                        "COST_MINOR_UNITS",
                        run.usage().costMinorUnits(),
                        run.budget().maxCostMinorUnits(),
                        percent(cost, run.budget().maxCostMinorUnits())));
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

    private static BudgetDimension minimum(BudgetDimension... values) {
        BudgetDimension result = values[0];
        for (BudgetDimension value : values) {
            if (value.remainingPercent() < result.remainingPercent()) result = value;
        }
        return result;
    }

    private record BudgetDimension(String resource, long used, long limit, int remainingPercent) {}
}
