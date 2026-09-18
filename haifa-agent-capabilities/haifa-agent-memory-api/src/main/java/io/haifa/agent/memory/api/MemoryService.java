package io.haifa.agent.memory.api;

import java.time.Instant;
import java.util.List;

public interface MemoryService {
    MemoryCandidate propose(MemoryCandidateDraft draft, MemoryActor actor);

    MemoryCandidate revise(
            MemoryCandidateId candidateId,
            MemoryCandidateDraft draft,
            long expectedRevision,
            MemoryActor actor,
            String idempotencyKey);

    Memory approve(MemoryCandidateId candidateId, MemoryActor actor, String idempotencyKey);

    Memory approve(MemoryCandidateId candidateId, long expectedRevision, MemoryActor actor, String idempotencyKey);

    MemoryCandidate reject(MemoryCandidateId candidateId, MemoryActor actor, String reason);

    MemoryCandidate reject(
            MemoryCandidateId candidateId,
            long expectedRevision,
            MemoryActor actor,
            String reason,
            String idempotencyKey);

    Memory invalidate(MemoryRef memory, MemoryActor actor, String reason, String idempotencyKey);

    MemoryCandidatePage queryCandidates(MemoryCandidateQuery query, MemoryActor actor);

    MemoryPage queryMemories(MemoryRecordQuery query, MemoryActor actor);

    Memory resolveConflict(
            String conflictId, MemoryConflictResolution resolution, MemoryActor actor, String idempotencyKey);

    List<MemoryRef> evaluateExpiry(Instant now);

    List<MemoryRef> invalidateSource(MemorySourceRef source, String reason, MemoryActor actor);

    List<MemoryRef> requestPurge(MemoryScope scope, String reason, MemoryActor actor);

    List<MemoryTombstone> executePurge(MemoryScope scope, String reason, MemoryActor actor);
}
