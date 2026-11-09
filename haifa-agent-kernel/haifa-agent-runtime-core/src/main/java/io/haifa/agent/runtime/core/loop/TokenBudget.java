package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.context.budget.ContextWindowBudget;
import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.prompt.PromptComponent;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ResolvedModelSnapshot;

/**
 * Runtime's single conservative budget and token-estimation entry point for normal context assembly.
 *
 * <p>Window calculation and structured value estimation are shared with Context and compaction.
 */
public final class TokenBudget {
    private static final String ESTIMATOR_VERSION = "runtime-structured-v1";

    private final ContextWindowBudget window;

    private TokenBudget(ContextWindowBudget window) {
        this.window = window;
    }

    static TokenBudget forModel(ResolvedModelSnapshot model, int requestedOutputTokens, int safetyMarginTokens) {
        return new TokenBudget(ContextWindowBudget.calculate(model, requestedOutputTokens, safetyMarginTokens));
    }

    ContextWindowBudget window() {
        return window;
    }

    long availableInputTokens() {
        return window.availableInputTokens();
    }

    int estimate(PromptComponent prompt) {
        return saturatedSum(tokens(prompt.text()), 6);
    }

    int estimate(ContextItem item) {
        return item.estimatedTokens();
    }

    int estimate(ModelToolSpecification tool) {
        return saturatedSum(tokens(tool.name()), tokens(tool.description()), tokens(tool.inputJsonSchema()), 20);
    }

    String estimatorVersion() {
        return ESTIMATOR_VERSION;
    }

    public static int tokens(String value) {
        return io.haifa.agent.context.budget.HeuristicTokenEstimator.tokens(value);
    }

    public static int tokens(Object value) {
        return io.haifa.agent.context.budget.HeuristicTokenEstimator.tokens(value);
    }

    private static int saturatedSum(int... values) {
        long result = 0L;
        for (int value : values) result = saturatedAdd(result, value);
        return (int) result;
    }

    private static long saturatedAdd(long left, long right) {
        return Math.min(Integer.MAX_VALUE, left + right);
    }
}
