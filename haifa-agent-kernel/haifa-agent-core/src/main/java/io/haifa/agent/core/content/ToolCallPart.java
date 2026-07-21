package io.haifa.agent.core.content;

import static io.haifa.agent.core.support.DomainValues.requireText;

import io.haifa.agent.core.tool.ToolArguments;
import java.util.Objects;

/** Provider-neutral assistant tool call persisted as part of the conversation. */
public record ToolCallPart(String toolCallId, String toolName, String toolVersion, ToolArguments arguments)
        implements ContentPart {
    public ToolCallPart {
        toolCallId = requireText(toolCallId, "toolCallId");
        toolName = requireText(toolName, "toolName");
        toolVersion = requireText(toolVersion, "toolVersion");
        arguments = Objects.requireNonNull(arguments, "arguments must not be null");
    }

    @Override
    public String contentType() {
        return "tool-call";
    }
}
