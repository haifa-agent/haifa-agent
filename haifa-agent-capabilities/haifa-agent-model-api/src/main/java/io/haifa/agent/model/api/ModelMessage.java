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
        boolean toolResultTruncated,
        Optional<SensitiveModelReasoning> reasoning,
        List<ModelImagePart> images,
        List<ModelAudioPart> audios) {
    public ModelMessage {
        role = Objects.requireNonNull(role, "role must not be null");
        content = Objects.requireNonNull(content, "content must not be null");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls must not be null"));
        providerCorrelationId = Objects.requireNonNull(providerCorrelationId, "providerCorrelationId must not be null");
        toolResultData = ModelValues.map(toolResultData, "toolResultData");
        reasoning = Objects.requireNonNull(reasoning, "reasoning must not be null");
        images = List.copyOf(Objects.requireNonNull(images, "images must not be null"));
        audios = List.copyOf(Objects.requireNonNull(audios, "audios must not be null"));
        if (images.size() > 4) {
            throw new IllegalArgumentException("model message may contain at most 4 images");
        }
        if (!images.isEmpty() && role != ModelMessageRole.USER) {
            throw new IllegalArgumentException("image inputs are only allowed on user messages");
        }
        if (audios.size() > 4) {
            throw new IllegalArgumentException("model message may contain at most 4 audio inputs");
        }
        if (!audios.isEmpty() && role != ModelMessageRole.USER) {
            throw new IllegalArgumentException("audio inputs are only allowed on user messages");
        }
        if (images.size() + audios.size() > 4) {
            throw new IllegalArgumentException("model message may contain at most 4 media inputs");
        }
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
        if (role != ModelMessageRole.ASSISTANT && reasoning.isPresent()) {
            throw new IllegalArgumentException("only assistant messages may contain reasoning");
        }
    }

    public ModelMessage(
            ModelMessageRole role,
            String content,
            List<ModelToolCall> toolCalls,
            Optional<ProviderToolCallCorrelationId> providerCorrelationId,
            Map<String, Object> toolResultData,
            boolean toolResultTruncated,
            Optional<SensitiveModelReasoning> reasoning) {
        this(
                role,
                content,
                toolCalls,
                providerCorrelationId,
                toolResultData,
                toolResultTruncated,
                reasoning,
                List.of(),
                List.of());
    }

    public ModelMessage(
            ModelMessageRole role,
            String content,
            List<ModelToolCall> toolCalls,
            Optional<ProviderToolCallCorrelationId> providerCorrelationId,
            Map<String, Object> toolResultData,
            boolean toolResultTruncated,
            Optional<SensitiveModelReasoning> reasoning,
            List<ModelImagePart> images) {
        this(
                role,
                content,
                toolCalls,
                providerCorrelationId,
                toolResultData,
                toolResultTruncated,
                reasoning,
                images,
                List.of());
    }

    public ModelMessage(
            ModelMessageRole role,
            String content,
            List<ModelToolCall> toolCalls,
            Optional<ProviderToolCallCorrelationId> providerCorrelationId,
            Map<String, Object> toolResultData,
            boolean toolResultTruncated) {
        this(role, content, toolCalls, providerCorrelationId, toolResultData, toolResultTruncated, Optional.empty());
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

    public static ModelMessage user(String content, List<? extends ModelImagePart> images) {
        return new ModelMessage(
                ModelMessageRole.USER,
                content,
                List.of(),
                Optional.empty(),
                Map.of(),
                false,
                Optional.empty(),
                List.copyOf(images),
                List.of());
    }

    public static ModelMessage user(
            String content, List<? extends ModelImagePart> images, List<? extends ModelAudioPart> audios) {
        return new ModelMessage(
                ModelMessageRole.USER,
                content,
                List.of(),
                Optional.empty(),
                Map.of(),
                false,
                Optional.empty(),
                List.copyOf(images),
                List.copyOf(audios));
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

    public static ModelMessage assistant(
            String content, List<ModelToolCall> toolCalls, SensitiveModelReasoning reasoning) {
        return new ModelMessage(
                ModelMessageRole.ASSISTANT,
                content,
                toolCalls,
                Optional.empty(),
                Map.of(),
                false,
                Optional.of(reasoning));
    }
}
