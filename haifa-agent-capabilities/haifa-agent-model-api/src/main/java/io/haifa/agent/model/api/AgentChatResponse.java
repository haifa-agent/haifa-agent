package io.haifa.agent.model.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Normalized chat response with no provider SDK types. */
public record AgentChatResponse(
        String responseId,
        String actualModelId,
        String content,
        List<ModelToolCall> toolCalls,
        ModelFinishReason finishReason,
        ModelUsage usage,
        String systemFingerprint,
        Map<String, Object> metadata,
        java.util.Optional<SensitiveModelReasoning> reasoning) {
    public AgentChatResponse {
        responseId = ModelValues.text(responseId, "responseId");
        actualModelId = ModelValues.text(actualModelId, "actualModelId");
        content = Objects.requireNonNull(content, "content must not be null");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls must not be null"));
        finishReason = Objects.requireNonNull(finishReason, "finishReason must not be null");
        usage = Objects.requireNonNull(usage, "usage must not be null");
        systemFingerprint = Objects.requireNonNull(systemFingerprint, "systemFingerprint must not be null")
                .trim();
        metadata = ModelValues.map(metadata, "metadata");
        reasoning = Objects.requireNonNull(reasoning, "reasoning must not be null");
        if (content.isBlank() && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("response must contain content or tool calls");
        }
    }

    public AgentChatResponse(
            String responseId,
            String actualModelId,
            String content,
            List<ModelToolCall> toolCalls,
            ModelFinishReason finishReason,
            ModelUsage usage,
            String systemFingerprint,
            Map<String, Object> metadata) {
        this(
                responseId,
                actualModelId,
                content,
                toolCalls,
                finishReason,
                usage,
                systemFingerprint,
                metadata,
                java.util.Optional.empty());
    }
}
