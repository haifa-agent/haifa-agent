package io.haifa.agent.context.item;

import io.haifa.agent.core.message.AgentMessage;
import java.util.List;
import java.util.Objects;

/** An atomic conversation group that context selection must keep or drop as a whole. */
public record MessageGroupContextContent(
        List<AgentMessage> messages,
        java.util.Map<io.haifa.agent.core.tool.ToolCallId, HistoricalExcerptContent> resultViews)
        implements ContextContent {
    public MessageGroupContextContent(List<AgentMessage> messages) {
        this(messages, java.util.Map.of());
    }

    public MessageGroupContextContent {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
        if (messages.isEmpty()) throw new IllegalArgumentException("message group must not be empty");
        resultViews = java.util.Map.copyOf(Objects.requireNonNull(resultViews));
        for (var entry : resultViews.entrySet()) {
            var view = entry.getValue();
            if (view.toolCallId().filter(entry.getKey()::equals).isEmpty()
                    || messages.stream()
                            .noneMatch(message -> message.id().equals(view.messageId())
                                    && message.runId()
                                            .filter(view.runId()::equals)
                                            .isPresent()
                                    && message.contents().stream()
                                            .anyMatch(part -> part
                                                            instanceof io.haifa.agent.core.content.ToolResultPart result
                                                    && result.toolCallId().equals(entry.getKey()))))
                throw new IllegalArgumentException("result view does not identify a grouped source");
        }
    }
}
