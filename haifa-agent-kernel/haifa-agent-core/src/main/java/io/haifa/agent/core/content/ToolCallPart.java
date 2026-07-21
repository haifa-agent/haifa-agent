package io.haifa.agent.core.content;

import static io.haifa.agent.core.support.DomainValues.requireText;

import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.ToolCallId;
import java.util.Objects;

/** Conversation reference to the canonical ToolCall aggregate. */
public record ToolCallPart(
        ToolCallId toolCallId, ProviderToolCallCorrelationId providerCorrelationId, String toolName, String toolVersion)
        implements ContentPart {
    public ToolCallPart {
        toolCallId = Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        providerCorrelationId = Objects.requireNonNull(providerCorrelationId, "providerCorrelationId must not be null");
        toolName = requireText(toolName, "toolName");
        toolVersion = requireText(toolVersion, "toolVersion");
    }

    @Override
    public String contentType() {
        return "tool-call-ref";
    }
}
