package io.haifa.agent.context.api;

import io.haifa.agent.context.budget.ContextWindowBudget;
import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.prompt.PromptComponent;
import io.haifa.agent.model.api.ModelToolSpecification;
import java.util.List;
import java.util.Objects;

/** Selected provider-neutral context IR for exactly one physical model invocation. */
public record AgentContext(
        List<PromptComponent> prompts,
        List<ContextItem> items,
        List<ModelToolSpecification> tools,
        ContextWindowBudget budget,
        long estimatedInputTokens) {
    public AgentContext {
        prompts = List.copyOf(Objects.requireNonNull(prompts, "prompts must not be null"));
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
        budget = Objects.requireNonNull(budget, "budget must not be null");
        if (estimatedInputTokens < 0 || estimatedInputTokens > budget.availableInputTokens()) {
            throw new IllegalArgumentException("estimatedInputTokens is outside the context budget");
        }
    }
}
