package io.haifa.agent.memory.api;

import java.util.Objects;

/** Authorized, bounded Memory text ready for Runtime to render into model context. */
public record MemorySnippet(
        MemoryId id, long revision, MemoryScope scope, String text, int estimatedTokens, String contentDigest) {
    public MemorySnippet {
        id = Objects.requireNonNull(id, "id must not be null");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        text = MemoryValues.text(text, "text", 16_384);
        if (estimatedTokens < 1) throw new IllegalArgumentException("estimatedTokens must be positive");
        contentDigest = MemoryValues.text(contentDigest, "contentDigest", 128);
    }
}
