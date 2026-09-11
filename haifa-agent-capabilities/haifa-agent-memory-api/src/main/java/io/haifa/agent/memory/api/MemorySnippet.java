package io.haifa.agent.memory.api;

import java.util.Objects;

/** Authorized, bounded Memory text ready for Runtime to render into model context. */
public record MemorySnippet(
        MemoryId id,
        MemoryVersion version,
        MemoryScope scope,
        String text,
        int estimatedTokens,
        String normalizedDigest) {
    public MemorySnippet {
        id = Objects.requireNonNull(id, "id must not be null");
        version = Objects.requireNonNull(version, "version must not be null");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        text = MemoryValues.text(text, "text", 16_384);
        if (estimatedTokens < 1) throw new IllegalArgumentException("estimatedTokens must be positive");
        normalizedDigest = MemoryValues.text(normalizedDigest, "normalizedDigest", 128);
    }
}
