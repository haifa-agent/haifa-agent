package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;

/** One assistant/tool display predicate shared by reads and committed-reference publication. */
public final class RunMessageVisibility {
    private RunMessageVisibility() {}

    public static boolean projectable(AgentMessage message) {
        if (message.status() == MessageStatus.REDACTED)
            return message.visibility() == MessageVisibility.REDACTED
                    && Boolean.TRUE.equals(message.metadata().get("runMessageProjected"))
                    && (message.role() == MessageRole.ASSISTANT || message.role() == MessageRole.TOOL);
        if (message.status() != MessageStatus.COMPLETED) return false;
        if (message.role() == MessageRole.ASSISTANT && message.visibility() == MessageVisibility.USER_VISIBLE) {
            return message.contents().stream()
                    .anyMatch(part -> part instanceof TextPart || part instanceof ToolCallPart);
        }
        if (message.role() == MessageRole.ASSISTANT && message.visibility() == MessageVisibility.AGENT_VISIBLE)
            return message.contents().stream().anyMatch(ToolCallPart.class::isInstance);
        return message.role() == MessageRole.TOOL
                && (message.visibility() == MessageVisibility.USER_VISIBLE
                        || message.visibility() == MessageVisibility.AGENT_VISIBLE)
                && message.contents().stream().anyMatch(ToolResultPart.class::isInstance);
    }
}
