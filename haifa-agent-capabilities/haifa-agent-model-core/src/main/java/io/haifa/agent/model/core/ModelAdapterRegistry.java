package io.haifa.agent.model.core;

import io.haifa.agent.model.api.AgentChatModel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable registry resolving protocol adapters by configured adapter type. */
public final class ModelAdapterRegistry {
    private final Map<String, AgentChatModel> adapters;

    public ModelAdapterRegistry(Map<String, AgentChatModel> adapters) {
        Objects.requireNonNull(adapters, "adapters must not be null");
        LinkedHashMap<String, AgentChatModel> copy = new LinkedHashMap<>();
        adapters.forEach((type, model) -> {
            String normalized = Objects.requireNonNull(type, "adapter type must not be null")
                    .trim();
            if (normalized.isEmpty()) throw new IllegalArgumentException("adapter type must not be blank");
            if (copy.putIfAbsent(normalized, Objects.requireNonNull(model, "adapter must not be null")) != null) {
                throw new IllegalArgumentException("duplicate adapter type: " + normalized);
            }
        });
        this.adapters = Map.copyOf(copy);
    }

    public AgentChatModel require(String adapterType) {
        AgentChatModel model = adapters.get(Objects.requireNonNull(adapterType).trim());
        if (model == null) throw new IllegalArgumentException("unknown model adapter: " + adapterType);
        return model;
    }
}
