package io.haifa.agent.runtime.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Safe message body, exact Tool references and optional model-call identity, without internal metadata or continuation. */
public record RunMessageView(
        String messageId,
        long sequence,
        long messageIndex,
        String role,
        String text,
        boolean textTruncated,
        List<ToolCallView> toolCalls,
        Map<String, String> toolCorrelations,
        long createdAtEpochMillis,
        Optional<String> modelCallId) {
    public RunMessageView {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(modelCallId, "modelCallId must not be null");
        if (modelCallId.isPresent() && modelCallId.orElseThrow().isBlank())
            throw new IllegalArgumentException("modelCallId must not be blank");
        if (sequence < 1 || messageIndex < 1) throw new IllegalArgumentException("message numbers must be positive");
        toolCalls = List.copyOf(toolCalls);
        toolCorrelations = Map.copyOf(toolCorrelations);
    }

    /** Compatibility constructor for messages without a model invocation or from older SDK consumers. */
    public RunMessageView(
            String messageId,
            long sequence,
            long messageIndex,
            String role,
            String text,
            boolean textTruncated,
            List<ToolCallView> toolCalls,
            Map<String, String> toolCorrelations,
            long createdAtEpochMillis) {
        this(
                messageId,
                sequence,
                messageIndex,
                role,
                text,
                textTruncated,
                toolCalls,
                toolCorrelations,
                createdAtEpochMillis,
                Optional.empty());
    }
}
