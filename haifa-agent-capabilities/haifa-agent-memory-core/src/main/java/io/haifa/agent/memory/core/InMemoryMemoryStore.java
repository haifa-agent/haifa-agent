package io.haifa.agent.memory.core;

import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryAuditEvent;
import io.haifa.agent.memory.api.MemoryAuditSink;
import io.haifa.agent.memory.api.MemoryCandidate;
import io.haifa.agent.memory.api.MemoryCandidateId;
import io.haifa.agent.memory.api.MemoryCandidateRepository;
import io.haifa.agent.memory.api.MemoryCandidateStatus;
import io.haifa.agent.memory.api.MemoryConflict;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryRef;
import io.haifa.agent.memory.api.MemoryRepository;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemoryStatus;
import io.haifa.agent.memory.api.MemoryTombstone;
import io.haifa.agent.memory.api.MemoryVersion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Thread-safe in-memory implementation of the candidate, memory, conflict, tombstone, and audit stores. */
public final class InMemoryMemoryStore implements MemoryCandidateRepository, MemoryRepository, MemoryAuditSink {
    private final Map<MemoryCandidateId, MemoryCandidate> candidates = new HashMap<>();
    private final Map<MemoryRef, Memory> memories = new HashMap<>();
    private final Map<MemoryId, MemoryVersion> latestVersions = new HashMap<>();
    private final Map<String, MemoryConflict> conflicts = new HashMap<>();
    private final List<MemoryTombstone> tombstones = new ArrayList<>();
    private final List<MemoryAuditEvent> auditEvents = new ArrayList<>();

    @Override
    public synchronized MemoryCandidate save(MemoryCandidate candidate) {
        candidates.put(candidate.id(), candidate);
        return candidate;
    }

    @Override
    public synchronized Optional<MemoryCandidate> find(MemoryCandidateId id) {
        return Optional.ofNullable(candidates.get(id));
    }

    @Override
    public synchronized Optional<MemoryCandidate> findByRequestKey(MemoryScope scope, String requestKey) {
        return candidates.values().stream()
                .filter(candidate -> candidate.scope().equals(scope)
                        && candidate.requestKey().equals(requestKey))
                .findFirst();
    }

    @Override
    public synchronized Optional<MemoryCandidate> findEquivalentPending(
            MemoryScope scope, MemoryKind kind, String normalizedDigest) {
        return candidates.values().stream()
                .filter(candidate -> candidate.scope().equals(scope)
                        && candidate.kind() == kind
                        && candidate.status() == MemoryCandidateStatus.PENDING
                        && candidate.normalizedDigest().equals(normalizedDigest))
                .findFirst();
    }

    @Override
    public synchronized List<MemoryCandidate> allCandidates() {
        return List.copyOf(candidates.values());
    }

    @Override
    public synchronized void purgeScope(MemoryScope scope) {
        candidates.entrySet().removeIf(entry -> entry.getValue().scope().equals(scope));
    }

    @Override
    public synchronized Memory save(Memory memory) {
        MemoryRef key = new MemoryRef(memory.id(), memory.version());
        memories.put(key, memory);
        MemoryVersion current = latestVersions.get(memory.id());
        if (current == null || memory.version().compareTo(current) >= 0)
            latestVersions.put(memory.id(), memory.version());
        return memory;
    }

    @Override
    public synchronized Optional<Memory> find(MemoryId id, MemoryVersion version) {
        return Optional.ofNullable(memories.get(new MemoryRef(id, version)));
    }

    @Override
    public synchronized Optional<Memory> latest(MemoryId id) {
        return Optional.ofNullable(latestVersions.get(id)).flatMap(version -> find(id, version));
    }

    @Override
    public synchronized Optional<Memory> findActiveEquivalent(
            MemoryScope scope, MemoryKind kind, String normalizedDigest) {
        return memories.values().stream()
                .filter(memory -> memory.scope().equals(scope)
                        && memory.kind() == kind
                        && memory.status() == MemoryStatus.ACTIVE
                        && memory.normalizedDigest().equals(normalizedDigest))
                .findFirst();
    }

    @Override
    public synchronized Optional<Memory> findActiveBySubject(MemoryScope scope, MemoryKind kind, String subjectKey) {
        return memories.values().stream()
                .filter(memory -> memory.scope().equals(scope)
                        && memory.kind() == kind
                        && memory.status() == MemoryStatus.ACTIVE
                        && memory.subjectKey().equals(subjectKey))
                .findFirst();
    }

    @Override
    public synchronized List<Memory> allMemories() {
        return List.copyOf(memories.values());
    }

    @Override
    public synchronized MemoryConflict saveConflict(MemoryConflict conflict) {
        conflicts.put(conflict.id(), conflict);
        return conflict;
    }

    @Override
    public synchronized Optional<MemoryConflict> conflictFor(MemoryCandidateId candidateId) {
        return conflicts.values().stream()
                .filter(conflict -> conflict.candidateId().equals(candidateId))
                .findFirst();
    }

    @Override
    public synchronized List<MemoryConflict> conflicts() {
        return List.copyOf(conflicts.values());
    }

    @Override
    public synchronized void saveTombstone(MemoryTombstone tombstone) {
        tombstones.add(tombstone);
    }

    @Override
    public synchronized List<MemoryTombstone> tombstones() {
        return List.copyOf(tombstones);
    }

    @Override
    public synchronized void record(MemoryAuditEvent event) {
        auditEvents.add(event);
    }

    public synchronized List<MemoryAuditEvent> auditEvents() {
        return List.copyOf(auditEvents);
    }
}
