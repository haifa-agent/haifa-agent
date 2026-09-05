package io.haifa.agent.context.compaction;

import java.util.Objects;

public record CompressionResult(ConversationSummary summary) {
    public CompressionResult {
        summary = Objects.requireNonNull(summary, "summary must not be null");
    }
}
