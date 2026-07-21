package io.haifa.agent.context.budget;

import io.haifa.agent.context.api.ContextBuildException;
import io.haifa.agent.context.api.ContextBuildFailure;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Objects;

/** Single-invocation budget, deliberately separate from cumulative AgentRunBudget. */
public record ContextWindowBudget(
        long modelContextWindow,
        long outputReserve,
        long safetyMargin,
        long availableInputTokens,
        long remainingRunInputTokens,
        long remainingRunOutputTokens) {
    public ContextWindowBudget {
        if (modelContextWindow < 1
                || outputReserve < 1
                || safetyMargin < 0
                || availableInputTokens < 0
                || remainingRunInputTokens < 0
                || remainingRunOutputTokens < 0) {
            throw new IllegalArgumentException("context window budget values are invalid");
        }
    }

    public static ContextWindowBudget calculate(
            ResolvedModelSnapshot model,
            AgentRunBudget runBudget,
            AgentRunUsage runUsage,
            int requestedOutputTokens,
            int safetyMarginTokens) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(runBudget, "runBudget must not be null");
        Objects.requireNonNull(runUsage, "runUsage must not be null");
        long remainingInput = Math.max(0L, runBudget.maxInputTokens() - runUsage.inputTokens());
        long remainingOutput = Math.max(0L, runBudget.maxOutputTokens() - runUsage.outputTokens());
        if (remainingInput == 0) {
            throw new ContextBuildException(
                    ContextBuildFailure.RUN_INPUT_BUDGET_EXHAUSTED, "run input token budget is exhausted");
        }
        if (remainingOutput == 0) {
            throw new ContextBuildException(
                    ContextBuildFailure.RUN_OUTPUT_BUDGET_EXHAUSTED, "run output token budget is exhausted");
        }
        long reserve = Math.min(Math.min((long) model.maxOutputTokens(), requestedOutputTokens), remainingOutput);
        long modelInput = (long) model.contextWindow() - reserve - safetyMarginTokens;
        if (modelInput < 1) {
            throw new ContextBuildException(
                    ContextBuildFailure.MODEL_WINDOW_TOO_SMALL,
                    "model context window cannot fit output reserve and safety margin");
        }
        return new ContextWindowBudget(
                model.contextWindow(),
                reserve,
                safetyMarginTokens,
                Math.min(modelInput, remainingInput),
                remainingInput,
                remainingOutput);
    }
}
