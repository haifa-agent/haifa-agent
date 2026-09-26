package io.haifa.agent.memory.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Input for {@code put}. The subject key identifies the fact inside its scope and kind: a second put with the
 * same subject replaces the content instead of adding another memory.
 *
 * <p>{@code observedAt} is the time the caller observed the source facts, for example the source message time of
 * an asynchronous extraction. When present, a write observed at or before the latest clear of the scope or the
 * deletion of the same subject is rejected with {@code MEMORY_WRITE_STALE}, so a late write cannot resurrect
 * cleared content. Synchronous explicit writes may omit it.
 */
public record MemoryDraft(
        MemoryScope scope,
        MemoryKind kind,
        String subjectKey,
        String content,
        Optional<MemorySourceRef> source,
        Optional<Instant> observedAt) {
    public MemoryDraft {
        scope = Objects.requireNonNull(scope, "scope must not be null");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        subjectKey = MemoryValues.subjectKey(subjectKey);
        content = MemoryValues.content(content);
        source = Objects.requireNonNull(source, "source must not be null");
        observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
    }

    public static MemoryDraft of(MemoryScope scope, MemoryKind kind, String subjectKey, String content) {
        return new MemoryDraft(scope, kind, subjectKey, content, Optional.empty(), Optional.empty());
    }

    /** True when this draft was observed at or before {@code change}, i.e. it must not overwrite that change. */
    public boolean observedNoLaterThan(java.time.Instant change) {
        return observedAt.filter(observed -> !observed.isAfter(change)).isPresent();
    }
}
