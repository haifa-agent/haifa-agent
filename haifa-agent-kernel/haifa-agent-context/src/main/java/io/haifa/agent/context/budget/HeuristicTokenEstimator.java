package io.haifa.agent.context.budget;

import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.prompt.PromptComponent;
import io.haifa.agent.model.api.ModelToolSpecification;

/** Deterministic conservative fallback until a model-specific tokenizer is registered. */
public final class HeuristicTokenEstimator implements TokenEstimator {
    @Override
    public int estimate(PromptComponent prompt) {
        return tokens(prompt.text()) + 4;
    }

    @Override
    public int estimate(ContextItem item) {
        return item.estimatedTokens();
    }

    @Override
    public int estimate(ModelToolSpecification tool) {
        return tokens(tool.name())
                + tokens(tool.description())
                + tokens(tool.inputJsonSchema().toString())
                + 12;
    }

    @Override
    public String version() {
        return "heuristic-chars-v1";
    }

    public static int tokens(String value) {
        long estimated = Math.max(1L, (value.length() + 3L) / 4L);
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, estimated));
    }
}
