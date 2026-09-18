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
        long reserve = Math.min((long) model.maxOutputTokens(), (long) requestedOutputTokens);
        long availableInput = (long) model.contextWindow() - reserve - safetyMarginTokens;
        if (availableInput < 1) {
            throw new ContextBuildException(
                    ContextBuildFailure.MODEL_WINDOW_TOO_SMALL,
                    "model context window cannot fit output reserve and safety margin");
        }
        return new ContextWindowBudget(model.contextWindow(), reserve, safetyMarginTokens, availableInput);
    }
}
