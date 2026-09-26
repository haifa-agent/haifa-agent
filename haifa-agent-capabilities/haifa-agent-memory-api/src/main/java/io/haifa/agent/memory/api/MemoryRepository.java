package io.haifa.agent.memory.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Trusted persistence port behind {@link MemoryService}. Callers have already authorized the scope; each method
 * is atomic on its own.
 */
public interface MemoryRepository {
    /**
     * Inserts a memory for the draft subject, replaces the live one, or returns it unchanged when the content is
     * identical. Throws {@code MEMORY_WRITE_STALE} when the draft was observed at or before the scope clear
     * watermark or the deletion of the same subject.
     */
    Memory upsert(MemoryDraft draft, MemoryId newId, Instant now);

    Optional<Memory> find(MemoryId id);

    /** Returns empty when the memory is missing, deleted, or not at {@code expectedRevision}. */
    Optional<Memory> update(MemoryId id, long expectedRevision, String content, Instant now);

    /** Returns false when the memory is missing, deleted, or not at {@code expectedRevision}. */
    boolean delete(MemoryId id, long expectedRevision, Instant now);

    MemoryPage list(MemoryQuery query);

    int clear(MemoryScope scope, Instant now);

    /** Newest live memories across the given scopes, bounded by {@code limit}, for Context retrieval. */
    List<Memory> recent(List<MemoryScope> scopes, int limit);
}
