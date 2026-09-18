package io.haifa.agent.memory.core;

import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryActor;
import io.haifa.agent.memory.api.MemoryAuditEvent;
import io.haifa.agent.memory.api.MemoryAuditStore;
import io.haifa.agent.memory.api.MemoryCandidate;
import io.haifa.agent.memory.api.MemoryCandidateId;
import io.haifa.agent.memory.api.MemoryCandidatePage;
import io.haifa.agent.memory.api.MemoryCandidateQuery;
import io.haifa.agent.memory.api.MemoryCandidateRepository;
import io.haifa.agent.memory.api.MemoryCandidateStatus;
import io.haifa.agent.memory.api.MemoryConflict;
import io.haifa.agent.memory.api.MemoryCursorCodec;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryPage;
import io.haifa.agent.memory.api.MemoryQuery;
import io.haifa.agent.memory.api.MemoryRecordQuery;
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
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe in-memory implementation of the candidate, memory, conflict, tombstone, and audit stores. */
public final class InMemoryMemoryStore
        implements MemoryCandidateRepository, MemoryRepository, MemoryAuditStore, AutoCloseable {
    private final Map<MemoryCandidateId, MemoryCandidate> candidates = new HashMap<>();
    private final Map<MemoryRef, Memory> memories = new HashMap<>();
    private final Map<MemoryId, MemoryVersion> latestVersions = new HashMap<>();
    private final Map<String, MemoryConflict> conflicts = new HashMap<>();
    private final List<MemoryTombstone> tombstones = new ArrayList<>();
    private final List<MemoryAuditEvent> auditEvents = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public synchronized MemoryCandidate save(MemoryCandidate candidate) {
        requireOpen();
        MemoryCandidate existing = candidates.get(candidate.id());
        if (existing != null && candidate.revision() > existing.revision() + 1) {
            throw new IllegalStateException("MEMORY_CANDIDATE_REVISION_STALE");
        }
        if (existing != null && candidate.revision() <= existing.revision() && !candidate.equals(existing)) {
            throw new IllegalStateException("MEMORY_CANDIDATE_REVISION_STALE");
        }
        candidates.put(candidate.id(), candidate);
        return candidate;
    }

    @Override
    public synchronized Optional<MemoryCandidate> find(MemoryCandidateId id) {
        requireOpen();
        return Optional.ofNullable(candidates.get(id));
    }

    @Override
    public synchronized Optional<MemoryCandidate> findAuthorized(MemoryCandidateId id, MemoryActor actor) {
        requireOpen();
        return Optional.ofNullable(candidates.get(id))
                .filter(candidate -> candidate.scope().tenant().equals(actor.tenant())
                        && candidate.scope().owner().equals(actor.principal()));
    }

    @Override
    public synchronized Optional<MemoryCandidate> findByRequestKey(MemoryScope scope, String requestKey) {
        requireOpen();
        return candidates.values().stream()
                .filter(candidate -> candidate.scope().equals(scope)
                        && candidate.requestKey().equals(requestKey))
                .findFirst();
    }

    @Override
    public synchronized Optional<MemoryCandidate> findEquivalentPending(
            MemoryScope scope, MemoryKind kind, String normalizedDigest) {
        requireOpen();
        return candidates.values().stream()
                .filter(candidate -> candidate.scope().equals(scope)
                        && candidate.kind() == kind
                        && candidate.status() == MemoryCandidateStatus.PENDING
                        && candidate.normalizedDigest().equals(normalizedDigest))
                .findFirst();
    }

    @Override
    public synchronized List<MemoryCandidate> allCandidates() {
        requireOpen();
        return List.copyOf(candidates.values());
    }

    @Override
    public synchronized MemoryCandidatePage query(MemoryCandidateQuery query) {
        requireOpen();
        var after = query.after().map(MemoryCursorCodec::decode);
        List<MemoryCandidate> ordered = candidates.values().stream()
                .filter(candidate -> candidate.scope().equals(query.scope()))
                .filter(candidate ->
                        query.statuses().isEmpty() || query.statuses().contains(candidate.status()))
                .filter(candidate -> query.kinds().isEmpty() || query.kinds().contains(candidate.kind()))
                .filter(candidate -> query.updatedBefore()
                        .map(before -> candidate.updatedAt().isBefore(before))
                        .orElse(true))
                .filter(candidate -> after.map(position -> candidate.updatedAt().isBefore(position.updatedAt())
                                || (candidate.updatedAt().equals(position.updatedAt())
                                        && candidate.id().value().compareTo(position.logicalId()) < 0))
                        .orElse(true))
                .sorted(java.util.Comparator.comparing(MemoryCandidate::updatedAt)
                        .reversed()
                        .thenComparing(candidate -> candidate.id().value(), java.util.Comparator.reverseOrder()))
                .limit((long) query.limit() + 1)
                .toList();
        boolean more = ordered.size() > query.limit();
        List<MemoryCandidate> items = more ? ordered.subList(0, query.limit()) : ordered;
        return new MemoryCandidatePage(
                items,
                more
                        ? Optional.of(MemoryCursorCodec.encode(
                                items.get(items.size() - 1).updatedAt(),
                                items.get(items.size() - 1).id().value(),
                                items.get(items.size() - 1).revision()))
                        : Optional.empty());
    }

    @Override
    public synchronized void purgeScope(MemoryScope scope) {
        requireOpen();
        candidates.entrySet().removeIf(entry -> entry.getValue().scope().equals(scope));
    }

    @Override
    public synchronized Memory save(Memory memory) {
        requireOpen();
        MemoryRef key = new MemoryRef(memory.id(), memory.version());
        memories.put(key, memory);
        MemoryVersion current = latestVersions.get(memory.id());
        if (current == null || memory.version().compareTo(current) >= 0)
            latestVersions.put(memory.id(), memory.version());
        return memory;
    }

    @Override
    public synchronized Optional<Memory> find(MemoryId id, MemoryVersion version) {
        requireOpen();
        return Optional.ofNullable(memories.get(new MemoryRef(id, version)));
    }

    @Override
    public synchronized Optional<Memory> findAuthorized(MemoryId id, MemoryVersion version, MemoryActor actor) {
        requireOpen();
        return Optional.ofNullable(memories.get(new MemoryRef(id, version)))
                .filter(memory -> memory.scope().tenant().equals(actor.tenant())
                        && memory.scope().owner().equals(actor.principal()));
    }

    @Override
    public synchronized Optional<Memory> latest(MemoryId id) {
        requireOpen();
        return Optional.ofNullable(latestVersions.get(id)).flatMap(version -> find(id, version));
    }

    @Override
    public synchronized Optional<Memory> findActiveEquivalent(
            MemoryScope scope, MemoryKind kind, String normalizedDigest) {
        requireOpen();
        return memories.values().stream()
                .filter(memory -> memory.scope().equals(scope)
                        && memory.kind() == kind
                        && memory.status() == MemoryStatus.ACTIVE
                        && memory.normalizedDigest().equals(normalizedDigest))
                .findFirst();
    }

    @Override
    public synchronized Optional<Memory> findActiveBySubject(MemoryScope scope, MemoryKind kind, String subjectKey) {
        requireOpen();
        return memories.values().stream()
                .filter(memory -> memory.scope().equals(scope)
                        && memory.kind() == kind
                        && memory.status() == MemoryStatus.ACTIVE
                        && memory.subjectKey().equals(subjectKey))
                .findFirst();
    }

    @Override
    public synchronized List<Memory> allMemories() {
        requireOpen();
        return List.copyOf(memories.values());
    }

    @Override
    public synchronized List<Memory> searchAuthorizedActive(MemoryQuery query, int fetchLimit) {
        requireOpen();
        if (fetchLimit < 1 || fetchLimit > 10_000) throw new IllegalArgumentException("fetchLimit is invalid");
        return memories.values().stream()
                .filter(memory -> memory.status() == MemoryStatus.ACTIVE)
                .filter(memory -> memory.scope().tenant().equals(query.tenant()))
                .filter(memory -> memory.scope().owner().equals(query.owner()))
                .filter(memory -> query.scopes().contains(memory.scope()))
                .filter(memory -> query.allowedSecurityLabels().containsAll(memory.securityLabels()))
                .filter(memory -> query.kinds().isEmpty() || query.kinds().contains(memory.kind()))
                .sorted(java.util.Comparator.comparing(Memory::updatedAt)
                        .reversed()
                        .thenComparing(memory -> memory.id().value(), java.util.Comparator.reverseOrder())
                        .thenComparing(Memory::version, java.util.Comparator.reverseOrder()))
                .limit(fetchLimit)
                .toList();
    }

    @Override
    public synchronized MemoryPage query(MemoryRecordQuery query) {
        requireOpen();
        var after = query.after().map(MemoryCursorCodec::decode);
        List<Memory> ordered = memories.values().stream()
                .filter(memory -> memory.scope().equals(query.scope()))
                .filter(memory -> query.statuses().isEmpty() || query.statuses().contains(memory.status()))
                .filter(memory -> query.kinds().isEmpty() || query.kinds().contains(memory.kind()))
                .filter(memory -> query.updatedBefore()
                        .map(before -> memory.updatedAt().isBefore(before))
                        .orElse(true))
                .filter(memory -> after.map(position -> memory.updatedAt().isBefore(position.updatedAt())
                                || (memory.updatedAt().equals(position.updatedAt())
                                        && (memory.id().value().compareTo(position.logicalId()) < 0
                                                || (memory.id().value().equals(position.logicalId())
                                                        && memory.version().value() < position.sequence()))))
                        .orElse(true))
                .sorted(java.util.Comparator.comparing(Memory::updatedAt)
                        .reversed()
                        .thenComparing(memory -> memory.id().value(), java.util.Comparator.reverseOrder())
                        .thenComparing(Memory::version, java.util.Comparator.reverseOrder()))
                .limit((long) query.limit() + 1)
                .toList();
        boolean more = ordered.size() > query.limit();
        List<Memory> items = more ? ordered.subList(0, query.limit()) : ordered;
        return new MemoryPage(
                items,
                more
                        ? Optional.of(MemoryCursorCodec.encode(
                                items.get(items.size() - 1).updatedAt(),
                                items.get(items.size() - 1).id().value(),
                                items.get(items.size() - 1).version().value()))
                        : Optional.empty());
    }

    @Override
    public synchronized MemoryConflict saveConflict(MemoryConflict conflict) {
        requireOpen();
        conflicts.put(conflict.id(), conflict);
        return conflict;
    }

    @Override
    public synchronized Optional<MemoryConflict> conflictFor(MemoryCandidateId candidateId) {
        requireOpen();
        return conflicts.values().stream()
                .filter(conflict -> conflict.candidateId().equals(candidateId))
                .findFirst();
    }

    @Override
    public synchronized List<MemoryConflict> conflicts() {
        requireOpen();
        return List.copyOf(conflicts.values());
    }

    @Override
    public synchronized void saveTombstone(MemoryTombstone tombstone) {
        requireOpen();
        tombstones.add(tombstone);
    }

    @Override
    public synchronized List<MemoryTombstone> tombstones() {
        requireOpen();
        return List.copyOf(tombstones);
    }

    @Override
    public synchronized void record(MemoryAuditEvent event) {
        requireOpen();
        if (event.idempotencyKeyDigest().isPresent()) {
            Optional<MemoryAuditEvent> existing = findByIdempotency(
                    event.scope(),
                    event.operation(),
                    event.idempotencyKeyDigest().orElseThrow());
            if (existing.isPresent()) {
                if (!existing.orElseThrow().requestDigest().equals(event.requestDigest())) {
                    throw new IllegalStateException("MEMORY_IDEMPOTENCY_CONFLICT");
                }
                return;
            }
        }
        auditEvents.add(event);
    }

    @Override
    public synchronized Optional<MemoryAuditEvent> findByIdempotency(
            MemoryScope scope, String operation, String idempotencyKeyDigest) {
        requireOpen();
        return auditEvents.stream()
                .filter(event -> event.scope().equals(scope)
                        && event.operation().equals(operation)
                        && event.idempotencyKeyDigest()
                                .filter(idempotencyKeyDigest::equals)
                                .isPresent())
                .findFirst();
    }

    public synchronized List<MemoryAuditEvent> auditEvents() {
        requireOpen();
        return List.copyOf(auditEvents);
    }

    @Override
    public void close() {
        closed.set(true);
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("MEMORY_STORE_CLOSED");
    }
}
