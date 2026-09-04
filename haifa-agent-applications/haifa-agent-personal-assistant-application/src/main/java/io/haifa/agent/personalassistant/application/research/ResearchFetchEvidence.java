package io.haifa.agent.personalassistant.application.research;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable safe projection of an authoritative web_fetch tool execution.
 *
 * <p>Exposes only verified network evidence without leaking untrusted raw page bodies into audit
 * boundaries.
 */
public record ResearchFetchEvidence(
        String toolCallId,
        String canonicalRequestedUrl,
        String canonicalFinalUrl,
        boolean successful,
        boolean sourceAvailable,
        Instant completedAt,
        String contentSha256,
        int contentCharacters,
        boolean truncated) {

    public ResearchFetchEvidence {
        toolCallId = Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        canonicalRequestedUrl =
                Objects.requireNonNull(canonicalRequestedUrl, "canonicalRequestedUrl must not be null");
        canonicalFinalUrl = Objects.requireNonNull(canonicalFinalUrl, "canonicalFinalUrl must not be null");
        completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        contentSha256 = Objects.requireNonNull(contentSha256, "contentSha256 must not be null");
    }
}
