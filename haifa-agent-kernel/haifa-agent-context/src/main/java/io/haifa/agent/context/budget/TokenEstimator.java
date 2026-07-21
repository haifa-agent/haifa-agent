package io.haifa.agent.context.budget;

import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.prompt.PromptComponent;
import io.haifa.agent.model.api.ModelToolSpecification;

public interface TokenEstimator {
    int estimate(PromptComponent prompt);

    int estimate(ContextItem item);

    int estimate(ModelToolSpecification tool);

    String version();
}
