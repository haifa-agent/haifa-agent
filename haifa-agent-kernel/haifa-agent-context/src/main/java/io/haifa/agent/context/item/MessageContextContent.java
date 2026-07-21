package io.haifa.agent.context.item;

import io.haifa.agent.core.message.AgentMessage;
import java.util.Objects;

public record MessageContextContent(AgentMessage message) implements ContextContent {
    public MessageContextContent {
        message = Objects.requireNonNull(message, "message must not be null");
    }
}
