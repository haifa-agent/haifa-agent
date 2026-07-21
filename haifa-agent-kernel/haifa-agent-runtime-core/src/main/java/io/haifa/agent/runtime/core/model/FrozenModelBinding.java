package io.haifa.agent.runtime.core.model;

import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import java.util.List;
import java.util.Objects;

/** One resolved runtime binding backed exclusively by a persisted frozen configuration snapshot. */
public record FrozenModelBinding(
        RuntimeConfigurationSnapshot configuration, AgentChatModel chatModel, List<ModelToolSpecification> tools) {
    public FrozenModelBinding {
        configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        chatModel = Objects.requireNonNull(chatModel, "chatModel must not be null");
        tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
    }
}
