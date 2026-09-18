package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.message.MessageCursor;
import java.util.Objects;
import java.util.Optional;

public record CodingCompactionResult(
        Optional<String> summaryReference,
        long summaryVersion,
        int sourceMessageCount,
        int estimatedTokens,
        MessageCursor through) {
    public CodingCompactionResult {
        summaryReference = Objects.requireNonNull(summaryReference, "summaryReference must not be null");
        if (summaryVersion < 0 || sourceMessageCount < 0 || estimatedTokens < 0) {
            throw new IllegalArgumentException("compaction metrics must not be negative");
        }
        through = Objects.requireNonNull(through, "through must not be null");
    }

    public String safeIndicator() {
        return summaryReference.isPresent()
                ? "context: summary v" + summaryVersion + " · ~" + estimatedTokens + " local-estimate tokens"
                : "context: recent window · no summary required";
    }
}
