package io.haifa.agent.memory.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One live long-term Memory. Deleted memories are never returned; {@code revision} is the optimistic
 * concurrency token for {@code update} and {@code delete}.
 */
public record Memory(
        MemoryId id,
        long revision,
        MemoryScope scope,
        MemoryKind kind,
        String subjectKey,
        String content,
        Optional<MemorySourceRef> source,
        Instant createdAt,
        Instant updatedAt) {
    public Memory {
        id = Objects.requireNonNull(id, "id must not be null");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        subjectKey = MemoryValues.subjectKey(subjectKey);
        content = MemoryValues.content(content);
        source = Objects.requireNonNull(source, "source must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }

    /** Deterministic, provider-neutral token estimate used for Context budgets. */
    public int estimatedTokens() {
        return MemoryValues.estimatedTokens(content);
    }

    /** True when {@code other} is the same content after whitespace normalization. */
    public boolean sameContent(String other) {
        return MemoryValues.normalizedContent(content).equals(MemoryValues.normalizedContent(other));
    }
}
