package io.haifa.agent.model.api;

import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import java.util.Map;
import java.util.Objects;

/** Provider-neutral function tool call with a protocol-only correlation identifier. */
public record ModelToolCall(
        ProviderToolCallCorrelationId providerCorrelationId, String name, Map<String, Object> arguments) {
    public ModelToolCall {
        providerCorrelationId = Objects.requireNonNull(providerCorrelationId, "providerCorrelationId must not be null");
        name = ModelValues.text(name, "name");
        arguments = ModelValues.map(arguments, "arguments");
    }
}
