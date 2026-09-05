package io.haifa.agent.personalassistant.application.research;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

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
        int contentBytes,
        boolean truncated) {

    private static final Pattern SHA256 = Pattern.compile("^sha256:[a-f0-9]{64}$");

    public ResearchFetchEvidence {
        toolCallId = Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        canonicalRequestedUrl = Objects.requireNonNull(canonicalRequestedUrl, "canonicalRequestedUrl must not be null");
        canonicalFinalUrl = Objects.requireNonNull(canonicalFinalUrl, "canonicalFinalUrl must not be null");
        completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        contentSha256 = Objects.requireNonNull(contentSha256, "contentSha256 must not be null");
        if (!SHA256.matcher(contentSha256).matches()) {
            throw new IllegalArgumentException("contentSha256 must be a canonical SHA-256 digest");
        }
        if (contentBytes < 0) {
            throw new IllegalArgumentException("contentBytes must not be negative");
        }
    }
}
