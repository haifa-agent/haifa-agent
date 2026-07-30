package io.haifa.agent.runtime.core.recovery;

import io.haifa.agent.core.run.AgentRun;
import java.time.Duration;
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
        int remainingPercent) {
    public static RunBudgetSnapshot from(
            AgentRun run, int iteration, int failureClusterAttempts, int completionRepairAttempts, Instant now) {
        long model = remaining(run.budget().maxModelCalls(), run.usage().modelCalls());
        long tools = remaining(run.budget().maxToolCalls(), run.usage().toolCalls());
        long iterations = Math.max(0, (long) run.limits().maxIterations() - iteration + 1L);
        long wall = Math.max(
                0,
                run.limits().maxWallTimeMillis()
                        - Math.max(0, Duration.between(run.createdAt(), now).toMillis()));
        long input = remaining(run.budget().maxInputTokens(), run.usage().inputTokens());
        long output = remaining(run.budget().maxOutputTokens(), run.usage().outputTokens());
        int percent = minPercent(
                percent(model, run.budget().maxModelCalls()),
                percent(tools, run.budget().maxToolCalls()),
                percent(iterations, run.limits().maxIterations()),
                percent(wall, run.limits().maxWallTimeMillis()),
                percent(input, run.budget().maxInputTokens()),
                percent(output, run.budget().maxOutputTokens()));
        return new RunBudgetSnapshot(
                model,
                tools,
                iterations,
                wall,
                input,
                output,
                Math.max(0, failureClusterAttempts),
                Math.max(0, completionRepairAttempts),
                percent);
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

    private static int minPercent(int... values) {
        int result = 100;
        for (int value : values) result = Math.min(result, value);
        return result;
    }
}
