package io.haifa.agent.memory.core;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryActor;
import io.haifa.agent.memory.api.MemoryAuditEvent;
import io.haifa.agent.memory.api.MemoryAuditSink;
import io.haifa.agent.memory.api.MemoryCandidate;
import io.haifa.agent.memory.api.MemoryCandidateDraft;
import io.haifa.agent.memory.api.MemoryCandidateId;
import io.haifa.agent.memory.api.MemoryCandidateRepository;
import io.haifa.agent.memory.api.MemoryCandidateStatus;
import io.haifa.agent.memory.api.MemoryConflict;
import io.haifa.agent.memory.api.MemoryConflictResolution;
import io.haifa.agent.memory.api.MemoryDerivedDataInvalidator;
import io.haifa.agent.memory.api.MemoryEvidenceVerifier;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryPolicy;
import io.haifa.agent.memory.api.MemoryRef;
import io.haifa.agent.memory.api.MemoryRepository;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemoryService;
import io.haifa.agent.memory.api.MemorySourceRef;
import io.haifa.agent.memory.api.MemoryStatus;
import io.haifa.agent.memory.api.MemoryTombstone;
import io.haifa.agent.memory.api.MemoryVersion;
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

/** Governed candidate-to-memory workflow. Candidate and Memory remain separate immutable aggregates. */
public final class DefaultMemoryService implements MemoryService {
    private final MemoryCandidateRepository candidates;
    private final MemoryRepository memories;
    private final MemoryPolicy policy;
    private final MemoryEvidenceVerifier evidenceVerifier;
    private final List<MemoryDerivedDataInvalidator> invalidators;
    private final MemoryAuditSink audit;
    private final IdentifierGenerator ids;
    private final TimeProvider time;

    public DefaultMemoryService(
            MemoryCandidateRepository candidates,
            MemoryRepository memories,
            MemoryPolicy policy,
            MemoryEvidenceVerifier evidenceVerifier,
            List<MemoryDerivedDataInvalidator> invalidators,
            MemoryAuditSink audit,
            IdentifierGenerator ids,
            TimeProvider time) {
        this.candidates = Objects.requireNonNull(candidates);
        this.memories = Objects.requireNonNull(memories);
        this.policy = Objects.requireNonNull(policy);
        this.evidenceVerifier = Objects.requireNonNull(evidenceVerifier);
        this.invalidators = List.copyOf(Objects.requireNonNull(invalidators));
        this.audit = Objects.requireNonNull(audit);
        this.ids = Objects.requireNonNull(ids);
        this.time = Objects.requireNonNull(time);
    }

    @Override
    public MemoryCandidate propose(MemoryCandidateDraft draft, MemoryActor actor) {
        Objects.requireNonNull(draft);
        require(policy.canPropose(actor, draft.scope()), "actor cannot propose memory in this scope");
        Optional<MemoryCandidate> priorRequest = candidates.findByRequestKey(draft.scope(), draft.requestKey());
        if (priorRequest.isPresent()) return priorRequest.orElseThrow();
        boolean sourcesMatchEvidence =
                draft.evidence().stream().allMatch(item -> draft.sources().contains(item.source()));
        if (!sourcesMatchEvidence
                || draft.evidence().stream().anyMatch(item -> !evidenceVerifier.verify(draft.scope(), item))) {
            throw new SecurityException("candidate evidence is not authoritative for the requested scope");
        }
        var decision = policy.evaluate(draft);
        String digest = digest(draft.kind() + "|" + normalize(draft.subjectKey()) + "|"
                + normalize(draft.content().boundedText()));
        Optional<MemoryCandidate> pending = candidates.findEquivalentPending(draft.scope(), draft.kind(), digest);
        if (pending.isPresent()) return pending.orElseThrow();

        MemoryCandidate candidate = new MemoryCandidate(
                new MemoryCandidateId(ids.nextValue()),
                draft.requestKey(),
                draft.scope(),
                draft.kind(),
                normalize(draft.subjectKey()),
                draft.content(),
                draft.sources(),
                draft.evidence(),
                MemoryCandidateStatus.PENDING,
                decision.labels(),
                digest,
                policy.version(),
                draft.retention(),
                time.now(),
                Optional.empty(),
                Optional.empty());

        Optional<Memory> equivalent = memories.findActiveEquivalent(draft.scope(), draft.kind(), digest);
        if (equivalent.isPresent()) {
            Memory existing = equivalent.orElseThrow();
            candidate = candidate.approve(new MemoryRef(existing.id(), existing.version()));
            candidates.save(candidate);
            record("candidate.duplicate", candidate, Optional.of(existing), actor, Map.of("digest", digest));
            return candidate;
        }

        candidates.save(candidate);
        MemoryCandidate proposed = candidate;
        memories.findActiveBySubject(draft.scope(), draft.kind(), proposed.subjectKey())
                .ifPresent(existing -> {
                    memories.saveConflict(new MemoryConflict(
                            ids.nextValue(),
                            new MemoryRef(existing.id(), existing.version()),
                            proposed.id(),
                            "same scope/kind/subject has incompatible normalized content",
                            policy.version(),
                            Optional.empty(),
                            Optional.empty(),
                            time.now(),
                            Optional.empty()));
                });
        record(
                "candidate.proposed",
                candidate,
                Optional.empty(),
                actor,
                Map.of("sensitive", Boolean.toString(decision.sensitive()), "digest", digest));
        if (draft.automaticApprovalRequested() && decision.automaticApprovalAllowed()) {
            approve(candidate.id(), actor, "auto:" + draft.requestKey());
            return requireCandidate(candidate.id());
        }
        return candidate;
    }

    @Override
    public Memory approve(MemoryCandidateId candidateId, MemoryActor actor, String idempotencyKey) {
        return approve(candidateId, actor, idempotencyKey, false).memory;
    }

    private Approval approve(
            MemoryCandidateId candidateId, MemoryActor actor, String idempotencyKey, boolean resolvingConflict) {
        MemoryCandidate candidate = requireCandidate(candidateId);
        require(policy.canReview(actor, candidate.scope()), "actor cannot approve memory in this scope");
        if (candidate.status() == MemoryCandidateStatus.APPROVED) {
            MemoryRef reference = candidate.approvedMemory().orElseThrow();
            return new Approval(
                    candidate,
                    memories.find(reference.id(), reference.version()).orElseThrow());
        }
        require(candidate.status() == MemoryCandidateStatus.PENDING, "candidate is not pending");
        if (!resolvingConflict
                && memories.conflictFor(candidateId)
                        .filter(value -> value.resolution().isEmpty())
                        .isPresent()) {
            throw new IllegalStateException("candidate has an unresolved memory conflict");
        }
        Memory memory = new Memory(
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
                candidate.retention(),
                time.now(),
                time.now());
        memories.save(memory);
        MemoryCandidate approved = candidate.approve(new MemoryRef(memory.id(), memory.version()));
        candidates.save(approved);
        record("candidate.approved", approved, Optional.of(memory), actor, Map.of("request", digest(idempotencyKey)));
        return new Approval(approved, memory);
    }

    @Override
    public MemoryCandidate reject(MemoryCandidateId candidateId, MemoryActor actor, String reason) {
        MemoryCandidate candidate = requireCandidate(candidateId);
        require(policy.canReview(actor, candidate.scope()), "actor cannot reject memory in this scope");
        if (candidate.status() == MemoryCandidateStatus.REJECTED) return candidate;
        MemoryCandidate rejected = candidate.reject(reason);
        candidates.save(rejected);
        record("candidate.rejected", rejected, Optional.empty(), actor, Map.of("reasonHash", digest(reason)));
        return rejected;
    }

    @Override
    public Memory resolveConflict(
            String conflictId, MemoryConflictResolution resolution, MemoryActor actor, String idempotencyKey) {
        MemoryConflict conflict = memories.conflicts().stream()
                .filter(value -> value.id().equals(conflictId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown memory conflict"));
        MemoryCandidate candidate = requireCandidate(conflict.candidateId());
        require(policy.canReview(actor, candidate.scope()), "actor cannot resolve memory conflict");
        if (conflict.resolution().isPresent()) {
            if (conflict.resolution().orElseThrow() != resolution
                    || !conflict.resolutionIdempotencyKey().orElseThrow().equals(idempotencyKey)) {
                throw new IllegalStateException("conflict already resolved differently");
            }
            return terminalConflictMemory(conflict);
        }
        Memory result;
        switch (resolution) {
            case KEEP_EXISTING, REJECT_CANDIDATE -> {
                candidates.save(candidate.reject("conflict resolved without candidate"));
                result = memories.find(
                                conflict.existingMemory().id(),
                                conflict.existingMemory().version())
                        .orElseThrow();
            }
            case KEEP_BOTH -> result = approve(candidate.id(), actor, idempotencyKey, true).memory;
            case REPLACE_WITH_CANDIDATE -> result = replace(conflict, candidate, actor, idempotencyKey);
            default -> throw new IllegalStateException("unsupported resolution");
        }
        memories.saveConflict(conflict.resolve(resolution, idempotencyKey, time.now()));
        record("conflict.resolved", candidate, Optional.of(result), actor, Map.of("resolution", resolution.name()));
        return result;
    }

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
                    MemoryRef reference = new MemoryRef(updated.id(), updated.version());
                    expired.add(reference);
                    invalidators.forEach(invalidator -> invalidator.invalidate(reference, "retention expired"));
                });
        return List.copyOf(expired);
    }

    @Override
    public List<MemoryRef> invalidateSource(MemorySourceRef source, String reason, MemoryActor actor) {
        List<MemoryRef> invalidated = new ArrayList<>();
        candidates.allCandidates().stream()
                .filter(candidate -> candidate.status() == MemoryCandidateStatus.PENDING
                        && candidate.sources().contains(source))
                .forEach(candidate -> candidates.save(candidate.expire("source invalidated")));
        memories.allMemories().stream()
                .filter(memory -> memory.status() == MemoryStatus.ACTIVE
                        && memory.sources().contains(source))
                .filter(memory -> policy.canReview(actor, memory.scope()))
                .forEach(memory -> {
                    Memory updated = memories.save(memory.transition(MemoryStatus.INVALIDATED, time.now()));
                    MemoryRef reference = new MemoryRef(updated.id(), updated.version());
                    invalidated.add(reference);
                    invalidators.forEach(invalidator -> invalidator.invalidate(reference, reason));
                    audit.record(new MemoryAuditEvent(
                            "memory.source-invalidated",
                            Optional.empty(),
                            Optional.of(reference),
                            updated.scope(),
                            actor.principal().principalId(),
                            Map.of("sourceType", source.type().name(), "sourceIdHash", digest(source.sourceId())),
                            time.now()));
                });
        return List.copyOf(invalidated);
    }

    @Override
    public List<MemoryRef> requestPurge(MemoryScope scope, String reason, MemoryActor actor) {
        require(policy.canPurge(actor, scope), "actor cannot purge this memory scope");
        List<MemoryRef> pending = new ArrayList<>();
        memories.allMemories().stream()
                .filter(memory -> memory.scope().equals(scope) && memory.status() != MemoryStatus.PURGED)
                .filter(memory -> memory.status() != MemoryStatus.PURGE_PENDING)
                .forEach(memory -> {
                    Memory updated = memories.save(memory.transition(MemoryStatus.PURGE_PENDING, time.now()));
                    pending.add(new MemoryRef(updated.id(), updated.version()));
                });
        audit.record(new MemoryAuditEvent(
                "memory.purge-requested",
                Optional.empty(),
                Optional.empty(),
                scope,
                actor.principal().principalId(),
                Map.of("reasonHash", digest(reason), "count", Integer.toString(pending.size())),
                time.now()));
        return List.copyOf(pending);
    }

    @Override
    public List<MemoryTombstone> executePurge(MemoryScope scope, String reason, MemoryActor actor) {
        require(policy.canPurge(actor, scope), "actor cannot purge this memory scope");
        List<MemoryTombstone> purged = new ArrayList<>();
        memories.allMemories().stream()
                .filter(memory -> memory.scope().equals(scope) && memory.status() == MemoryStatus.PURGE_PENDING)
                .forEach(memory -> {
                    String formerDigest = memory.normalizedDigest();
                    Memory updated = memories.save(memory.transition(MemoryStatus.PURGED, time.now()));
                    MemoryRef reference = new MemoryRef(updated.id(), updated.version());
                    MemoryTombstone tombstone = new MemoryTombstone(reference, scope, formerDigest, reason, time.now());
                    memories.saveTombstone(tombstone);
                    purged.add(tombstone);
                    invalidators.forEach(invalidator -> invalidator.invalidate(reference, "memory purged"));
                });
        candidates.purgeScope(scope);
        audit.record(new MemoryAuditEvent(
                "memory.purged",
                Optional.empty(),
                Optional.empty(),
                scope,
                actor.principal().principalId(),
                Map.of("reasonHash", digest(reason), "count", Integer.toString(purged.size())),
                time.now()));
        return List.copyOf(purged);
    }

    private Memory replace(
            MemoryConflict conflict, MemoryCandidate candidate, MemoryActor actor, String idempotencyKey) {
        Memory existing = memories.find(
                        conflict.existingMemory().id(),
                        conflict.existingMemory().version())
                .orElseThrow();
        memories.save(existing.transition(MemoryStatus.SUPERSEDED, time.now()));
        Memory replacement = new Memory(
                existing.id(),
                new MemoryVersion(existing.version().value() + 1),
                candidate.scope(),
                candidate.kind(),
                candidate.subjectKey(),
                Optional.of(candidate.content()),
                candidate.sources(),
                candidate.evidence(),
                MemoryStatus.ACTIVE,
                candidate.securityLabels(),
                candidate.normalizedDigest(),
                Optional.of(new MemoryRef(existing.id(), existing.version())),
                candidate.retention(),
                time.now(),
                time.now());
        memories.save(replacement);
        MemoryCandidate approved = candidate.approve(new MemoryRef(replacement.id(), replacement.version()));
        candidates.save(approved);
        record(
                "candidate.approved-replacement",
                approved,
                Optional.of(replacement),
                actor,
                Map.of("request", digest(idempotencyKey)));
        return replacement;
    }

    private Memory terminalConflictMemory(MemoryConflict conflict) {
        MemoryCandidate candidate = requireCandidate(conflict.candidateId());
        return candidate
                .approvedMemory()
                .flatMap(reference -> memories.find(reference.id(), reference.version()))
                .orElseGet(() -> memories.find(
                                conflict.existingMemory().id(),
                                conflict.existingMemory().version())
                        .orElseThrow());
    }

    private MemoryCandidate requireCandidate(MemoryCandidateId id) {
        return candidates.find(id).orElseThrow(() -> new IllegalArgumentException("unknown memory candidate"));
    }

    private void record(
            String operation,
            MemoryCandidate candidate,
            Optional<Memory> memory,
            MemoryActor actor,
            Map<String, String> attributes) {
        audit.record(new MemoryAuditEvent(
                operation,
                Optional.of(candidate.id()),
                memory.map(value -> new MemoryRef(value.id(), value.version())),
                candidate.scope(),
                actor.principal().principalId(),
                attributes,
                time.now()));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new SecurityException(message);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String digest(String value) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record Approval(MemoryCandidate approvedCandidate, Memory memory) {}
}
