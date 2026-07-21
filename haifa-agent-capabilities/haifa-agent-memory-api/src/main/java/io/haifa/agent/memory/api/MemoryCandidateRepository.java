package io.haifa.agent.memory.api;

import java.util.List;
import java.util.Optional;

public interface MemoryCandidateRepository {
    MemoryCandidate save(MemoryCandidate candidate);

    Optional<MemoryCandidate> find(MemoryCandidateId id);

    Optional<MemoryCandidate> findByRequestKey(MemoryScope scope, String requestKey);

    Optional<MemoryCandidate> findEquivalentPending(MemoryScope scope, MemoryKind kind, String normalizedDigest);

    List<MemoryCandidate> allCandidates();

    void purgeScope(MemoryScope scope);
}
