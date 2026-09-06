package io.haifa.agent.context.compression;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable point-in-time snapshot of the latest valid summary and latest summary version.
 */
public record SummarySnapshot(Optional<ConversationSummary> latestValid, long latestVersion) {
    public SummarySnapshot {
        Objects.requireNonNull(latestValid, "latestValid must not be null");
    }
}
