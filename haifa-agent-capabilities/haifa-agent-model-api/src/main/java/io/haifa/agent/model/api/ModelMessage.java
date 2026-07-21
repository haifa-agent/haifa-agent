package io.haifa.agent.model.api;

import java.util.List;
import java.util.Objects;

/** One provider-neutral chat message, including tool-call correlation when applicable. */
public record ModelMessage(ModelMessageRole role, String content, List<ModelToolCall> toolCalls, String toolCallId) {
    public ModelMessage {
        role = Objects.requireNonNull(role, "role must not be null");
        content = Objects.requireNonNull(content, "content must not be null");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls must not be null"));
        toolCallId = Objects.requireNonNull(toolCallId, "toolCallId must not be null")
                .trim();
        if (role == ModelMessageRole.ASSISTANT && content.isBlank() && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("assistant message must contain content or tool calls");
        }
        if (role == ModelMessageRole.TOOL && toolCallId.isBlank()) {
            throw new IllegalArgumentException("tool message requires toolCallId");
        }
        if (role != ModelMessageRole.TOOL && !toolCallId.isEmpty()) {
            throw new IllegalArgumentException("only tool messages may contain toolCallId");
        }
        if (role != ModelMessageRole.ASSISTANT && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException("only assistant messages may contain toolCalls");
        }
    }

    public static ModelMessage text(ModelMessageRole role, String content) {
        return new ModelMessage(role, content, List.of(), "");
    }
}
