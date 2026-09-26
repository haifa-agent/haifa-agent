package io.haifa.agent.memory.api;

import java.util.Optional;

/**
 * Direct Memory use cases. Every call is bounded by the trusted {@link MemoryActor}: a caller can only touch
 * scopes it owns, and foreign or missing memories are reported identically as {@code MEMORY_UNAVAILABLE}.
 */
public interface MemoryService {
    /** Creates the memory, replaces the content of the live memory with the same subject, or returns it unchanged. */
    Memory put(MemoryDraft draft, MemoryActor actor);

    /**
     * Compare-and-set content update; {@code MEMORY_REVISION_STALE} when the revision moved on. Retrying with the
     * same revision and content after the first attempt committed returns the current memory instead of failing.
     */
    Memory update(MemoryId id, long expectedRevision, String content, MemoryActor actor);

    /**
     * Compare-and-set delete. Deleted content is erased and never recalled or listed again. Retrying with the same
     * revision after the first attempt committed succeeds; any other revision of a deleted memory is
     * {@code MEMORY_UNAVAILABLE}.
     */
    void delete(MemoryId id, long expectedRevision, MemoryActor actor);

    Optional<Memory> find(MemoryId id, MemoryActor actor);

    MemoryPage list(MemoryQuery query, MemoryActor actor);

    /**
     * Deletes every memory in the scope and records a clear watermark against late writes; returns the count. Clear is
     * idempotent in state: a retry leaves the scope empty and reports {@code 0} (or whatever was written since).
     */
    int clear(MemoryScope scope, MemoryActor actor);
}
