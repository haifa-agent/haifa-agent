package io.haifa.agent.core.content;

import static io.haifa.agent.core.support.DomainValues.requireText;

import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.ToolCallId;
import java.util.Objects;

/** Bounded conversation summary referencing the canonical ToolCall result. */
public record ToolResultPart(ToolCallId toolCallId, ProviderToolCallCorrelationId providerCorrelationId, String summary)
        implements ContentPart {
    public ToolResultPart {
        toolCallId = Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        providerCorrelationId = Objects.requireNonNull(providerCorrelationId, "providerCorrelationId must not be null");
        summary = requireText(summary, "summary");
    }

    @Override
    public String contentType() {
        return "tool-result-ref";
    }
}
