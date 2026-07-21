package io.haifa.agent.core.content;

import static io.haifa.agent.core.support.DomainValues.requireText;

/** Provider-neutral tool result correlated to the assistant call that requested it. */
public record ToolResultPart(String toolCallId, String text) implements ContentPart {
    public ToolResultPart {
        toolCallId = requireText(toolCallId, "toolCallId");
        text = requireText(text, "text");
    }

    @Override
    public String contentType() {
        return "tool-result";
    }
}
