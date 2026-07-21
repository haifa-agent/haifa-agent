package io.haifa.agent.memory.api;

import java.util.List;
import java.util.Optional;

public interface MemoryRepository {
    Memory save(Memory memory);

    Optional<Memory> find(MemoryId id, MemoryVersion version);

    Optional<Memory> latest(MemoryId id);

    Optional<Memory> findActiveEquivalent(MemoryScope scope, MemoryKind kind, String normalizedDigest);

    Optional<Memory> findActiveBySubject(MemoryScope scope, MemoryKind kind, String subjectKey);

    List<Memory> allMemories();

    MemoryConflict saveConflict(MemoryConflict conflict);

    Optional<MemoryConflict> conflictFor(MemoryCandidateId candidateId);

    List<MemoryConflict> conflicts();

    void saveTombstone(MemoryTombstone tombstone);

    List<MemoryTombstone> tombstones();
}
