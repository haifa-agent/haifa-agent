package io.haifa.agent.context.item;

import io.haifa.agent.core.message.AgentMessage;
import java.util.List;
import java.util.Objects;

/** An atomic conversation group that context selection must keep or drop as a whole. */
public record MessageGroupContextContent(List<AgentMessage> messages) implements ContextContent {
    public MessageGroupContextContent {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
        if (messages.isEmpty()) throw new IllegalArgumentException("message group must not be empty");
    }
}
