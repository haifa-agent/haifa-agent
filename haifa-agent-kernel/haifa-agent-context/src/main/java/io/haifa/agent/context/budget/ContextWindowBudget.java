package io.haifa.agent.context.budget;

import io.haifa.agent.context.api.ContextBuildException;
import io.haifa.agent.context.api.ContextBuildFailure;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Objects;

/** Single-invocation budget, decoupled from cumulative AgentRun quota. */
public record ContextWindowBudget(
        long modelContextWindow, long outputReserve, long safetyMargin, long availableInputTokens) {
    public ContextWindowBudget {
        if (modelContextWindow < 1 || outputReserve < 1 || safetyMargin < 0 || availableInputTokens < 0) {
            throw new IllegalArgumentException("context window budget values are invalid");
        }
    }

    public static ContextWindowBudget calculate(
            ResolvedModelSnapshot model, int requestedOutputTokens, int safetyMarginTokens) {
        Objects.requireNonNull(model, "model must not be null");
        if (requestedOutputTokens < 1) {
            throw new IllegalArgumentException("requestedOutputTokens must be positive");
        }
        if (safetyMarginTokens < 0) {
            throw new IllegalArgumentException("safetyMarginTokens must not be negative");
        }
        var budget = capacity(
                model.contextWindow(),
                Math.min((long) model.maxOutputTokens(), requestedOutputTokens),
                safetyMarginTokens);
        if (budget.availableInputTokens() < 1) {
            throw new ContextBuildException(
                    ContextBuildFailure.MODEL_WINDOW_TOO_SMALL,
                    "model context window cannot fit output reserve and safety margin");
        }
        return budget;
    }

    /** Shared calculation for actual requests and compaction planning; zero means no input can be admitted. */
    public static ContextWindowBudget capacity(long contextWindow, long outputReserve, long safetyMargin) {
        if (contextWindow < 1 || outputReserve < 1 || safetyMargin < 0)
            throw new IllegalArgumentException("invalid context capacity");
        long afterOutput = outputReserve >= contextWindow ? 0 : contextWindow - outputReserve;
        long available = safetyMargin >= afterOutput ? 0 : afterOutput - safetyMargin;
        return new ContextWindowBudget(contextWindow, outputReserve, safetyMargin, available);
    }

    public static int safetyMargin(long contextWindow, boolean forcedRebuild) {
        if (contextWindow < 1) throw new IllegalArgumentException("context window must be positive");
        return (int) Math.min(16_384L, Math.max(256L, contextWindow / (forcedRebuild ? 10L : 20L)));
    }
}
