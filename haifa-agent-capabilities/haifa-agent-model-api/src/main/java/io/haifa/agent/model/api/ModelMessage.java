package io.haifa.agent.model.api;

import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One provider-neutral chat message, including typed tool-call correlation when applicable. */
public record ModelMessage(
        ModelMessageRole role,
        String content,
        List<ModelToolCall> toolCalls,
        Optional<ProviderToolCallCorrelationId> providerCorrelationId,
        Map<String, Object> toolResultData,
        boolean toolResultTruncated) {
    public ModelMessage {
        role = Objects.requireNonNull(role, "role must not be null");
        content = Objects.requireNonNull(content, "content must not be null");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls must not be null"));
        providerCorrelationId = Objects.requireNonNull(providerCorrelationId, "providerCorrelationId must not be null");
        toolResultData = ModelValues.map(toolResultData, "toolResultData");
        if (role == ModelMessageRole.ASSISTANT && content.isBlank() && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("assistant message must contain content or tool calls");
        }
        if (role == ModelMessageRole.TOOL && providerCorrelationId.isEmpty()) {
            throw new IllegalArgumentException("tool message requires providerCorrelationId");
        }
        if (role != ModelMessageRole.TOOL && providerCorrelationId.isPresent()) {
            throw new IllegalArgumentException("only tool messages may contain providerCorrelationId");
        }
        if (role != ModelMessageRole.ASSISTANT && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException("only assistant messages may contain toolCalls");
        }
        if (role != ModelMessageRole.TOOL && (!toolResultData.isEmpty() || toolResultTruncated)) {
            throw new IllegalArgumentException("only tool messages may contain tool result data");
        }
    }

    public ModelMessage(
            ModelMessageRole role,
            String content,
            List<ModelToolCall> toolCalls,
            Optional<ProviderToolCallCorrelationId> providerCorrelationId) {
        this(role, content, toolCalls, providerCorrelationId, Map.of(), false);
    }

    public static ModelMessage text(ModelMessageRole role, String content) {
        return new ModelMessage(role, content, List.of(), Optional.empty());
    }

    public static ModelMessage tool(ProviderToolCallCorrelationId correlationId, String content) {
        return tool(correlationId, content, Map.of(), false);
    }

    public static ModelMessage tool(
            ProviderToolCallCorrelationId correlationId,
            String content,
            Map<String, Object> structuredData,
            boolean truncated) {
        return new ModelMessage(
                ModelMessageRole.TOOL, content, List.of(), Optional.of(correlationId), structuredData, truncated);
    }

    public static ModelMessage assistant(String content, List<ModelToolCall> toolCalls) {
        return new ModelMessage(ModelMessageRole.ASSISTANT, content, toolCalls, Optional.empty());
    }
}
