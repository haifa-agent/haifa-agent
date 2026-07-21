package io.haifa.agent.core.tool;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

/** Provider protocol identifier that correlates an assistant tool call with its tool response. */
public record ProviderToolCallCorrelationId(String value) implements Identifier {
    public ProviderToolCallCorrelationId {
        value = Identifiers.requireValid(value, "providerToolCallCorrelationId");
    }
}
