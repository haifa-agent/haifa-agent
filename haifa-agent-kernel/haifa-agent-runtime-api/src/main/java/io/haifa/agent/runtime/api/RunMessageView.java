package io.haifa.agent.runtime.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Safe message body and exact Tool references, without internal message metadata or continuation. */
public record RunMessageView(
        String messageId,
        long sequence,
        long messageIndex,
        String role,
        String text,
        boolean textTruncated,
        List<ToolCallView> toolCalls,
        Map<String, String> toolCorrelations,
        long createdAtEpochMillis) {
    public RunMessageView {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(text, "text must not be null");
        if (sequence < 1 || messageIndex < 1) throw new IllegalArgumentException("message numbers must be positive");
        toolCalls = List.copyOf(toolCalls);
        toolCorrelations = Map.copyOf(toolCorrelations);
    }
}
