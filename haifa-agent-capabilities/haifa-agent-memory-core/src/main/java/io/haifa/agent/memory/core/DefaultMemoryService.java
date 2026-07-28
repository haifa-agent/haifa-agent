package io.haifa.agent.memory.core;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryActor;
import io.haifa.agent.memory.api.MemoryAuditEvent;
import io.haifa.agent.memory.api.MemoryAuditSink;
import io.haifa.agent.memory.api.MemoryAuditStore;
import io.haifa.agent.memory.api.MemoryCandidate;
import io.haifa.agent.memory.api.MemoryCandidateDraft;
import io.haifa.agent.memory.api.MemoryCandidateId;
import io.haifa.agent.memory.api.MemoryCandidatePage;
import io.haifa.agent.memory.api.MemoryCandidateQuery;
import io.haifa.agent.memory.api.MemoryCandidateRepository;
import io.haifa.agent.memory.api.MemoryCandidateStatus;
import io.haifa.agent.memory.api.MemoryConflictResolution;
import io.haifa.agent.memory.api.MemoryDerivedDataInvalidator;
import io.haifa.agent.memory.api.MemoryEvidenceVerifier;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryOperationException;
import io.haifa.agent.memory.api.MemoryPage;
import io.haifa.agent.memory.api.MemoryPolicy;
import io.haifa.agent.memory.api.MemoryRecordQuery;
import io.haifa.agent.memory.api.MemoryRef;
import io.haifa.agent.memory.api.MemoryRepository;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemoryService;
import io.haifa.agent.memory.api.MemorySourceRef;
import io.haifa.agent.memory.api.MemoryStatus;
import io.haifa.agent.memory.api.MemoryTombstone;
import io.haifa.agent.memory.api.MemoryUnitOfWork;
import io.haifa.agent.memory.api.MemoryVersion;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Governed candidate-to-memory workflow.
 *
 * <p>The product path is deliberately manual-review-only. Candidate, immutable Memory versions, and
 * safe audit events are committed through one injected unit of work.
 */
public final class DefaultMemoryService implements MemoryService {
    private static final String REPLACED = "REPLACED";

    private final MemoryCandidateRepository candidates;
    private final MemoryRepository memories;
    private final MemoryPolicy policy;
    private final MemoryEvidenceVerifier evidenceVerifier;
    private final List<MemoryDerivedDataInvalidator> invalidators;
    private final MemoryAuditStore audit;
    private final IdentifierGenerator ids;
    private final TimeProvider time;
    private final MemoryUnitOfWork unitOfWork;

    public DefaultMemoryService(
            MemoryCandidateRepository candidates,
            MemoryRepository memories,
            MemoryPolicy policy,
            MemoryEvidenceVerifier evidenceVerifier,
            List<MemoryDerivedDataInvalidator> invalidators,
            MemoryAuditSink audit,
            IdentifierGenerator ids,
            TimeProvider time) {
        this(
                candidates,
                memories,
                policy,
                evidenceVerifier,
                invalidators,
                audit instanceof MemoryAuditStore store ? store : new LocalAuditStore(audit),
                ids,
                time,
                MemoryUnitOfWork.direct());
    }

    public DefaultMemoryService(
            MemoryCandidateRepository candidates,
            MemoryRepository memories,
            MemoryPolicy policy,
            MemoryEvidenceVerifier evidenceVerifier,
            List<MemoryDerivedDataInvalidator> invalidators,
            MemoryAuditStore audit,
            IdentifierGenerator ids,
            TimeProvider time,
            MemoryUnitOfWork unitOfWork) {
        this.candidates = Objects.requireNonNull(candidates, "candidates must not be null");
        this.memories = Objects.requireNonNull(memories, "memories must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.evidenceVerifier = Objects.requireNonNull(evidenceVerifier, "evidenceVerifier must not be null");
        this.invalidators = List.copyOf(Objects.requireNonNull(invalidators, "invalidators must not be null"));
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
    }

    @Override
    public MemoryCandidate propose(MemoryCandidateDraft draft, MemoryActor actor) {
        Objects.requireNonNull(draft, "draft must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        return unitOfWork.execute(() -> proposeInside(draft, actor));
    }

    private MemoryCandidate proposeInside(MemoryCandidateDraft draft, MemoryActor actor) {
        require(policy.canPropose(actor, draft.scope()), "MEMORY_UNAVAILABLE");
        String requestKeyDigest = digestParts(draft.requestKey());
        Optional<MemoryCandidate> priorRequest = candidates.findByRequestKey(draft.scope(), requestKeyDigest);
        if (priorRequest.isPresent()) return priorRequest.orElseThrow();
        verifyEvidence(draft);
        var decision = policy.evaluate(draft);
        String subject = normalize(draft.subjectKey());
        String digest =
                contentDigest(draft.kind().name(), subject, draft.content().boundedText());
        Optional<MemoryCandidate> pending = candidates.findEquivalentPending(draft.scope(), draft.kind(), digest);
        if (pending.isPresent() && pending.orElseThrow().replacesMemoryRef().equals(draft.replacesMemoryRef())) {
            return pending.orElseThrow();
        }

        Optional<Memory> active = memories.findActiveBySubject(draft.scope(), draft.kind(), subject);
        validateReplacement(draft, active);
        Optional<MemoryRef> conflict = active.map(DefaultMemoryService::reference);
        Instant now = time.now();
        MemoryCandidate candidate = new MemoryCandidate(
                new MemoryCandidateId(ids.nextValue()),
                requestKeyDigest,
                draft.scope(),
                draft.kind(),
                subject,
                draft.content(),
                draft.sources(),
                draft.evidence(),
                MemoryCandidateStatus.PENDING,
                decision.labels(),
                digest,
                policy.version(),
                draft.retention(),
                now,
                now,
                0,
                draft.replacesMemoryRef(),
                conflict,
                Optional.empty(),
                Optional.empty());
        candidates.save(candidate);
        record(
                "candidate.proposed",
                candidate,
                Optional.empty(),
                actor,
                Map.of(
                        "sensitive",
                        Boolean.toString(decision.sensitive()),
                        "automaticApprovalIgnored",
                        Boolean.toString(draft.automaticApprovalRequested()),
                        "contentDigest",
                        digest),
                Optional.empty(),
                Optional.empty());
        return candidate;
    }

    @Override
    public MemoryCandidate revise(
            MemoryCandidateId candidateId,
            MemoryCandidateDraft draft,
            long expectedRevision,
            MemoryActor actor,
            String idempotencyKey) {
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        Objects.requireNonNull(draft, "draft must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        return unitOfWork.execute(() -> {
            MemoryCandidate candidate = requireCandidate(candidateId, actor);
            require(policy.canReview(actor, candidate.scope()), "MEMORY_UNAVAILABLE");
            require(candidate.scope().equals(draft.scope()), "MEMORY_UNAVAILABLE");
            require(candidate.requestKey().equals(digestParts(draft.requestKey())), "MEMORY_REQUEST_KEY_MISMATCH");
            String requestDigest = digestParts(
                    "revise",
                    candidate.id().value(),
                    Long.toString(expectedRevision),
                    draft.kind().name(),
                    normalize(draft.subjectKey()),
                    draft.content().boundedText(),
                    draft.replacesMemoryRef()
                            .map(DefaultMemoryService::externalRef)
                            .orElse(""));
            Optional<MemoryAuditEvent> replay =
                    replay(candidate.scope(), "candidate.revised", idempotencyKey, requestDigest);
            if (replay.isPresent()) return requireCandidate(candidateId, actor);
            requireRevision(candidate.revision(), expectedRevision);
            require(candidate.status() == MemoryCandidateStatus.PENDING, "MEMORY_CANDIDATE_NOT_PENDING");
            require(candidate.kind() == draft.kind(), "MEMORY_CANDIDATE_KIND_IMMUTABLE");
            verifyEvidence(draft);
            var decision = policy.evaluate(draft);
            String subject = normalize(draft.subjectKey());
            String digest =
                    contentDigest(draft.kind().name(), subject, draft.content().boundedText());
            Optional<Memory> active = memories.findActiveBySubject(draft.scope(), draft.kind(), subject);
            validateReplacement(draft, active);
            MemoryCandidate revised = candidate.revise(
                    subject,
                    draft.content(),
                    draft.sources(),
                    draft.evidence(),
                    decision.labels(),
                    digest,
                    policy.version(),
                    draft.retention(),
                    draft.replacesMemoryRef(),
                    active.map(DefaultMemoryService::reference),
                    time.now());
            candidates.save(revised);
            record(
                    "candidate.revised",
                    revised,
                    Optional.empty(),
                    actor,
                    Map.of("contentDigest", digest),
                    Optional.of(idempotencyKey),
                    Optional.of(requestDigest));
            return revised;
        });
    }

    @Override
    public Memory approve(MemoryCandidateId candidateId, MemoryActor actor, String idempotencyKey) {
        MemoryCandidate candidate = requireCandidate(candidateId, actor);
        long expectedRevision =
                candidate.status() == MemoryCandidateStatus.APPROVED ? candidate.revision() - 1 : candidate.revision();
        return approve(candidateId, expectedRevision, actor, idempotencyKey);
    }

    @Override
    public Memory approve(
            MemoryCandidateId candidateId, long expectedRevision, MemoryActor actor, String idempotencyKey) {
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        return unitOfWork.execute(() -> approveInside(candidateId, expectedRevision, actor, idempotencyKey));
    }

    private Memory approveInside(
            MemoryCandidateId candidateId, long expectedRevision, MemoryActor actor, String idempotencyKey) {
        MemoryCandidate candidate = requireCandidate(candidateId, actor);
        require(policy.canReview(actor, candidate.scope()), "MEMORY_UNAVAILABLE");
        String requestDigest = digestParts("approve", candidate.id().value(), Long.toString(expectedRevision));
        Optional<MemoryAuditEvent> replay =
                replay(candidate.scope(), "candidate.approved", idempotencyKey, requestDigest);
        if (replay.isPresent()) {
            MemoryRef result = replay.orElseThrow().memory().orElseThrow();
            return memories.findAuthorized(result.id(), result.version(), actor)
                    .orElseThrow(() -> failure("MEMORY_UNAVAILABLE"));
        }
        requireRevision(candidate.revision(), expectedRevision);
        require(candidate.status() == MemoryCandidateStatus.PENDING, "MEMORY_CANDIDATE_NOT_PENDING");
        verifyEvidence(candidate);

        Optional<Memory> active =
                memories.findActiveBySubject(candidate.scope(), candidate.kind(), candidate.subjectKey());
        boolean replacement = candidate.replacesMemoryRef().isPresent();
        if (active.isPresent()
                && !candidate
                        .replacesMemoryRef()
                        .filter(reference(active.orElseThrow())::equals)
                        .isPresent()) {
            throw failure("MEMORY_SUBJECT_CONFLICT");
        }
        if (replacement && active.isEmpty()) throw failure("MEMORY_REPLACEMENT_TARGET_INVALID");

        Instant now = time.now();
        Memory created;
        Optional<MemoryRef> invalidated = Optional.empty();
        if (replacement) {
            Memory previous = active.orElseThrow();
            MemoryRef createdRef = new MemoryRef(
                    previous.id(), new MemoryVersion(previous.version().value() + 1));
            memories.save(previous.invalidate(REPLACED, Optional.of(createdRef), now));
            invalidated = Optional.of(reference(previous));
            created = new Memory(
                    createdRef.id(),
                    createdRef.version(),
                    candidate.scope(),
                    candidate.kind(),
                    candidate.subjectKey(),
                    Optional.of(candidate.content()),
                    candidate.sources(),
                    candidate.evidence(),
                    MemoryStatus.ACTIVE,
                    candidate.securityLabels(),
                    candidate.normalizedDigest(),
                    Optional.of(reference(previous)),
                    Optional.empty(),
                    Optional.empty(),
                    candidate.retention(),
                    now,
                    now);
        } else {
            created = new Memory(
                    new MemoryId(ids.nextValue()),
                    new MemoryVersion(1),
                    candidate.scope(),
                    candidate.kind(),
                    candidate.subjectKey(),
                    Optional.of(candidate.content()),
                    candidate.sources(),
                    candidate.evidence(),
                    MemoryStatus.ACTIVE,
                    candidate.securityLabels(),
                    candidate.normalizedDigest(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    candidate.retention(),
                    now,
                    now);
        }
        memories.save(created);
        MemoryCandidate approved = candidate.approve(reference(created), now);
        candidates.save(approved);
        record(
                "candidate.approved",
                approved,
                Optional.of(created),
                actor,
                Map.of("replacement", Boolean.toString(replacement)),
                Optional.of(idempotencyKey),
                Optional.of(requestDigest));
        invalidated.ifPresent(reference -> unitOfWork.afterCommit(() -> invalidateDerived(reference, REPLACED)));
        return created;
    }

    @Override
    public MemoryCandidate reject(MemoryCandidateId candidateId, MemoryActor actor, String reason) {
        MemoryCandidate candidate = requireCandidate(candidateId, actor);
        return reject(candidateId, candidate.revision(), actor, reason, "legacy:" + candidate.requestKey());
    }

    @Override
    public MemoryCandidate reject(
            MemoryCandidateId candidateId,
            long expectedRevision,
            MemoryActor actor,
            String reason,
            String idempotencyKey) {
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        return unitOfWork.execute(() -> {
            MemoryCandidate candidate = requireCandidate(candidateId, actor);
            require(policy.canReview(actor, candidate.scope()), "MEMORY_UNAVAILABLE");
            String requestDigest =
                    digestParts("reject", candidate.id().value(), Long.toString(expectedRevision), reason);
            Optional<MemoryAuditEvent> replay =
                    replay(candidate.scope(), "candidate.rejected", idempotencyKey, requestDigest);
            if (replay.isPresent()) return requireCandidate(candidateId, actor);
            requireRevision(candidate.revision(), expectedRevision);
            require(candidate.status() == MemoryCandidateStatus.PENDING, "MEMORY_CANDIDATE_NOT_PENDING");
            MemoryCandidate rejected = candidate.reject(reason, time.now());
            candidates.save(rejected);
            record(
                    "candidate.rejected",
                    rejected,
                    Optional.empty(),
                    actor,
                    Map.of("reasonDigest", digestParts(reason)),
                    Optional.of(idempotencyKey),
                    Optional.of(requestDigest));
            return rejected;
        });
    }

    @Override
    public Memory invalidate(MemoryRef reference, MemoryActor actor, String reason, String idempotencyKey) {
        Objects.requireNonNull(reference, "reference must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        return unitOfWork.execute(() -> {
            Memory memory = memories.findAuthorized(reference.id(), reference.version(), actor)
                    .orElseThrow(() -> failure("MEMORY_UNAVAILABLE"));
            require(policy.canReview(actor, memory.scope()), "MEMORY_UNAVAILABLE");
            String requestDigest = digestParts("invalidate", externalRef(reference), reason);
            Optional<MemoryAuditEvent> replay =
                    replay(memory.scope(), "memory.invalidated", idempotencyKey, requestDigest);
            if (replay.isPresent()) {
                return memories.findAuthorized(reference.id(), reference.version(), actor)
                        .orElseThrow(() -> failure("MEMORY_UNAVAILABLE"));
            }
            require(memory.status() == MemoryStatus.ACTIVE, "MEMORY_NOT_ACTIVE");
            require(!REPLACED.equals(reason), "MEMORY_REPLACEMENT_REQUIRES_CANDIDATE");
            Memory invalidated = memories.save(memory.invalidate(reason, Optional.empty(), time.now()));
            record(
                    "memory.invalidated",
                    Optional.empty(),
                    Optional.of(invalidated),
                    invalidated.scope(),
                    actor,
                    Map.of("reasonDigest", digestParts(reason)),
                    Optional.of(idempotencyKey),
                    Optional.of(requestDigest),
                    Optional.empty());
            unitOfWork.afterCommit(() -> invalidateDerived(reference, reason));
            return invalidated;
        });
    }

    @Override
    public MemoryCandidatePage queryCandidates(MemoryCandidateQuery query, MemoryActor actor) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        requireScope(actor, query.scope());
        return candidates.query(query);
    }

    @Override
    public MemoryPage queryMemories(MemoryRecordQuery query, MemoryActor actor) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        requireScope(actor, query.scope());
        return memories.query(query);
    }

    @Override
    public Memory resolveConflict(
            String conflictId, MemoryConflictResolution resolution, MemoryActor actor, String idempotencyKey) {
        throw failure("MEMORY_CONFLICT_MANAGEMENT_DEFERRED");
    }

    /**
     * Legacy deferred lifecycle operation retained for source compatibility. The Phase 2 SQLite
     * provider does not implement expiry state or persistence.
     */
    @Override
    public List<MemoryRef> evaluateExpiry(Instant now) {
        List<MemoryRef> expired = new ArrayList<>();
        candidates.allCandidates().stream()
                .filter(candidate -> candidate.status() == MemoryCandidateStatus.PENDING)
                .filter(candidate -> candidate
                        .retention()
                        .expiresAt()
                        .map(expiresAt -> !expiresAt.isAfter(now))
                        .orElse(false))
                .forEach(candidate -> candidates.save(candidate.expire("candidate retention expired")));
        memories.allMemories().stream()
                .filter(memory -> memory.status() == MemoryStatus.ACTIVE && memory.expiredAt(now))
                .forEach(memory -> {
                    Memory updated = memories.save(memory.transition(MemoryStatus.EXPIRED, now));
                    MemoryRef reference = reference(updated);
                    expired.add(reference);
                    invalidators.forEach(invalidator -> invalidator.invalidate(reference, "retention expired"));
                });
        return List.copyOf(expired);
    }

    @Override
    public List<MemoryRef> invalidateSource(MemorySourceRef source, String reason, MemoryActor actor) {
        List<MemoryRef> invalidated = new ArrayList<>();
        memories.allMemories().stream()
                .filter(memory -> memory.status() == MemoryStatus.ACTIVE
                        && memory.sources().contains(source))
                .filter(memory -> policy.canReview(actor, memory.scope()))
                .forEach(memory -> {
                    Memory updated =
                            memories.save(memory.invalidate("SOURCE_INVALIDATED", Optional.empty(), time.now()));
                    MemoryRef reference = reference(updated);
                    invalidated.add(reference);
                    invalidators.forEach(invalidator -> invalidator.invalidate(reference, reason));
                });
        return List.copyOf(invalidated);
    }

    @Override
    public List<MemoryRef> requestPurge(MemoryScope scope, String reason, MemoryActor actor) {
        require(policy.canPurge(actor, scope), "MEMORY_UNAVAILABLE");
        List<MemoryRef> pending = new ArrayList<>();
        memories.allMemories().stream()
                .filter(memory -> memory.scope().equals(scope) && memory.status() != MemoryStatus.PURGED)
                .filter(memory -> memory.status() != MemoryStatus.PURGE_PENDING)
                .forEach(memory -> {
                    Memory updated = memories.save(memory.transition(MemoryStatus.PURGE_PENDING, time.now()));
                    pending.add(reference(updated));
                });
        return List.copyOf(pending);
    }

    @Override
    public List<MemoryTombstone> executePurge(MemoryScope scope, String reason, MemoryActor actor) {
        require(policy.canPurge(actor, scope), "MEMORY_UNAVAILABLE");
        List<MemoryTombstone> purged = new ArrayList<>();
        memories.allMemories().stream()
                .filter(memory -> memory.scope().equals(scope) && memory.status() == MemoryStatus.PURGE_PENDING)
                .forEach(memory -> {
                    Memory updated = memories.save(memory.transition(MemoryStatus.PURGED, time.now()));
                    MemoryTombstone tombstone = new MemoryTombstone(
                            reference(updated), scope, memory.normalizedDigest(), reason, time.now());
                    memories.saveTombstone(tombstone);
                    purged.add(tombstone);
                });
        candidates.purgeScope(scope);
        return List.copyOf(purged);
    }

    private MemoryCandidate requireCandidate(MemoryCandidateId id, MemoryActor actor) {
        return candidates.findAuthorized(id, actor).orElseThrow(() -> failure("MEMORY_UNAVAILABLE"));
    }

    private void verifyEvidence(MemoryCandidateDraft draft) {
        boolean sourcesMatchEvidence =
                draft.evidence().stream().allMatch(item -> draft.sources().contains(item.source()));
        if (!sourcesMatchEvidence
                || draft.evidence().stream().anyMatch(item -> !evidenceVerifier.verify(draft.scope(), item))) {
            throw failure("MEMORY_EVIDENCE_UNAVAILABLE");
        }
    }

    private void verifyEvidence(MemoryCandidate candidate) {
        if (candidate.evidence().stream().anyMatch(item -> !evidenceVerifier.verify(candidate.scope(), item))) {
            throw failure("MEMORY_EVIDENCE_UNAVAILABLE");
        }
    }

    private void validateReplacement(MemoryCandidateDraft draft, Optional<Memory> active) {
        if (draft.replacesMemoryRef().isEmpty()) return;
        MemoryRef expected = draft.replacesMemoryRef().orElseThrow();
        if (active.isEmpty() || !reference(active.orElseThrow()).equals(expected)) {
            throw failure("MEMORY_REPLACEMENT_TARGET_INVALID");
        }
    }

    private Optional<MemoryAuditEvent> replay(
            MemoryScope scope, String operation, String idempotencyKey, String requestDigest) {
        String keyDigest = digestParts(idempotencyKey);
        Optional<MemoryAuditEvent> existing = audit.findByIdempotency(scope, operation, keyDigest);
        if (existing.isPresent()
                && !existing.orElseThrow()
                        .requestDigest()
                        .filter(requestDigest::equals)
                        .isPresent()) {
            throw failure("MEMORY_IDEMPOTENCY_CONFLICT");
        }
        return existing;
    }

    private void record(
            String operation,
            MemoryCandidate candidate,
            Optional<Memory> memory,
            MemoryActor actor,
            Map<String, String> attributes,
            Optional<String> idempotencyKey,
            Optional<String> requestDigest) {
        record(
                operation,
                Optional.of(candidate),
                memory,
                candidate.scope(),
                actor,
                attributes,
                idempotencyKey,
                requestDigest,
                Optional.of(candidate.revision()));
    }

    private void record(
            String operation,
            Optional<MemoryCandidate> candidate,
            Optional<Memory> memory,
            MemoryScope scope,
            MemoryActor actor,
            Map<String, String> attributes,
            Optional<String> idempotencyKey,
            Optional<String> requestDigest,
            Optional<Long> candidateRevision) {
        audit.record(new MemoryAuditEvent(
                operation,
                candidate.map(MemoryCandidate::id),
                memory.map(DefaultMemoryService::reference),
                scope,
                actor.principal().principalId(),
                attributes,
                time.now(),
                idempotencyKey.map(DefaultMemoryService::digestParts),
                requestDigest,
                candidateRevision));
    }

    private void invalidateDerived(MemoryRef reference, String reason) {
        invalidators.forEach(invalidator -> invalidator.invalidate(reference, reason));
    }

    private static void requireScope(MemoryActor actor, MemoryScope scope) {
        require(actor.tenant().equals(scope.tenant()) && actor.principal().equals(scope.owner()), "MEMORY_UNAVAILABLE");
    }

    private static void requireRevision(long actual, long expected) {
        require(actual == expected, "MEMORY_CANDIDATE_REVISION_STALE");
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw failure(code);
    }

    private static MemoryOperationException failure(String code) {
        return new MemoryOperationException(code);
    }

    private static MemoryRef reference(Memory memory) {
        return new MemoryRef(memory.id(), memory.version());
    }

    private static String externalRef(MemoryRef reference) {
        return reference.id().value() + "@" + reference.version().value();
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String contentDigest(String kind, String subject, String content) {
        return digestParts(kind, subject, normalize(content));
    }

    private static String digestParts(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = Objects.requireNonNull(value, "digest value must not be null")
                        .getBytes(StandardCharsets.UTF_8);
                digest.update(
                        ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static final class LocalAuditStore implements MemoryAuditStore {
        private record Key(MemoryScope scope, String operation, String keyDigest) {}

        private final MemoryAuditSink delegate;
        private final Map<Key, MemoryAuditEvent> events = new ConcurrentHashMap<>();

        private LocalAuditStore(MemoryAuditSink delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        }

        @Override
        public Optional<MemoryAuditEvent> findByIdempotency(
                MemoryScope scope, String operation, String idempotencyKeyDigest) {
            return Optional.ofNullable(events.get(new Key(scope, operation, idempotencyKeyDigest)));
        }

        @Override
        public void record(MemoryAuditEvent event) {
            event.idempotencyKeyDigest().ifPresent(key -> {
                MemoryAuditEvent prior = events.putIfAbsent(new Key(event.scope(), event.operation(), key), event);
                if (prior != null && !prior.requestDigest().equals(event.requestDigest())) {
                    throw failure("MEMORY_IDEMPOTENCY_CONFLICT");
                }
            });
            delegate.record(event);
        }
    }
}
