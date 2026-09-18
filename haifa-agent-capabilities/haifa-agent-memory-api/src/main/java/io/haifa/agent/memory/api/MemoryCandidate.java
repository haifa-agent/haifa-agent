package io.haifa.agent.memory.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record MemoryCandidate(
        MemoryCandidateId id,
        String requestKey,
        MemoryScope scope,
        MemoryKind kind,
        String subjectKey,
        MemoryContent content,
        List<MemorySourceRef> sources,
        List<MemoryEvidenceRef> evidence,
        MemoryCandidateStatus status,
        Set<MemorySecurityLabel> securityLabels,
        String normalizedDigest,
        String policyVersion,
        MemoryRetentionPolicy retention,
        Instant createdAt,
        Instant updatedAt,
        long revision,
        Optional<MemoryRef> replacesMemoryRef,
        Optional<MemoryRef> conflictingMemoryRef,
        Optional<MemoryRef> approvedMemory,
        Optional<String> dispositionReason) {
    public MemoryCandidate {
        id = Objects.requireNonNull(id, "id must not be null");
        requestKey = MemoryValues.text(requestKey, "requestKey", 256);
        scope = Objects.requireNonNull(scope, "scope must not be null");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        subjectKey = MemoryValues.text(subjectKey, "subjectKey", 256);
        content = Objects.requireNonNull(content, "content must not be null");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
        if (sources.isEmpty() || evidence.isEmpty()) throw new IllegalArgumentException("candidate requires evidence");
        status = Objects.requireNonNull(status, "status must not be null");
        securityLabels = Set.copyOf(Objects.requireNonNull(securityLabels, "securityLabels must not be null"));
        normalizedDigest = MemoryValues.text(normalizedDigest, "normalizedDigest", 128);
        policyVersion = MemoryValues.text(policyVersion, "policyVersion", 128);
        retention = Objects.requireNonNull(retention, "retention must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt must not precede createdAt");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        replacesMemoryRef = Objects.requireNonNull(replacesMemoryRef, "replacesMemoryRef must not be null");
        conflictingMemoryRef = Objects.requireNonNull(conflictingMemoryRef, "conflictingMemoryRef must not be null");
        approvedMemory = Objects.requireNonNull(approvedMemory, "approvedMemory must not be null");
        dispositionReason = Objects.requireNonNull(dispositionReason, "dispositionReason must not be null");
        if (status == MemoryCandidateStatus.APPROVED && approvedMemory.isEmpty()) {
            throw new IllegalArgumentException("approved candidate requires memory reference");
        }
        if (status != MemoryCandidateStatus.APPROVED && approvedMemory.isPresent()) {
            throw new IllegalArgumentException("only approved candidate may reference memory");
        }
    }

    public MemoryCandidate(
            MemoryCandidateId id,
            String requestKey,
            MemoryScope scope,
            MemoryKind kind,
            String subjectKey,
            MemoryContent content,
            List<MemorySourceRef> sources,
            List<MemoryEvidenceRef> evidence,
            MemoryCandidateStatus status,
            Set<MemorySecurityLabel> securityLabels,
            String normalizedDigest,
            String policyVersion,
            MemoryRetentionPolicy retention,
            Instant createdAt,
            Optional<MemoryRef> approvedMemory,
            Optional<String> dispositionReason) {
        this(
                id,
                requestKey,
                scope,
                kind,
                subjectKey,
                content,
                sources,
                evidence,
                status,
                securityLabels,
                normalizedDigest,
                policyVersion,
                retention,
                createdAt,
                createdAt,
                0,
                Optional.empty(),
                Optional.empty(),
                approvedMemory,
                dispositionReason);
    }

    public MemoryCandidate approve(MemoryRef reference, Instant at) {
        if (status == MemoryCandidateStatus.APPROVED) return this;
        if (status != MemoryCandidateStatus.PENDING) throw new IllegalStateException("candidate is not pending");
        return copy(
                MemoryCandidateStatus.APPROVED,
                subjectKey,
                content,
                sources,
                evidence,
                securityLabels,
                normalizedDigest,
                policyVersion,
                retention,
                replacesMemoryRef,
                conflictingMemoryRef,
                Optional.of(reference),
                Optional.empty(),
                Objects.requireNonNull(at),
                revision + 1);
    }

    public MemoryCandidate approve(MemoryRef reference) {
        return approve(reference, updatedAt);
    }

    public MemoryCandidate reject(String reason, Instant at) {
        if (status == MemoryCandidateStatus.REJECTED) return this;
        if (status != MemoryCandidateStatus.PENDING) throw new IllegalStateException("candidate is not pending");
        return copy(
                MemoryCandidateStatus.REJECTED,
                subjectKey,
                content,
                sources,
                evidence,
                securityLabels,
                normalizedDigest,
                policyVersion,
                retention,
                replacesMemoryRef,
                conflictingMemoryRef,
                Optional.empty(),
                Optional.of(MemoryValues.text(reason, "reason", 512)),
                Objects.requireNonNull(at),
                revision + 1);
    }

    public MemoryCandidate reject(String reason) {
        return reject(reason, updatedAt);
    }

    public MemoryCandidate expire(String reason) {
        if (status == MemoryCandidateStatus.EXPIRED) return this;
        if (status != MemoryCandidateStatus.PENDING) throw new IllegalStateException("candidate is not pending");
        return copy(
                MemoryCandidateStatus.EXPIRED,
                subjectKey,
                content,
                sources,
                evidence,
                securityLabels,
                normalizedDigest,
                policyVersion,
                retention,
                replacesMemoryRef,
                conflictingMemoryRef,
                Optional.empty(),
                Optional.of(MemoryValues.text(reason, "reason", 512)),
                updatedAt,
                revision + 1);
    }

    public MemoryCandidate revise(
            String revisedSubjectKey,
            MemoryContent revisedContent,
            List<MemorySourceRef> revisedSources,
            List<MemoryEvidenceRef> revisedEvidence,
            Set<MemorySecurityLabel> revisedLabels,
            String revisedDigest,
            String revisedPolicyVersion,
            MemoryRetentionPolicy revisedRetention,
            Optional<MemoryRef> revisedReplacement,
            Optional<MemoryRef> revisedConflict,
            Instant at) {
        if (status != MemoryCandidateStatus.PENDING) throw new IllegalStateException("candidate is not pending");
        return copy(
                MemoryCandidateStatus.PENDING,
                revisedSubjectKey,
                revisedContent,
                revisedSources,
                revisedEvidence,
                revisedLabels,
                revisedDigest,
                revisedPolicyVersion,
                revisedRetention,
                revisedReplacement,
                revisedConflict,
                Optional.empty(),
                Optional.empty(),
                Objects.requireNonNull(at),
                revision + 1);
    }

    private MemoryCandidate copy(
            MemoryCandidateStatus newStatus,
            String newSubjectKey,
            MemoryContent newContent,
            List<MemorySourceRef> newSources,
            List<MemoryEvidenceRef> newEvidence,
            Set<MemorySecurityLabel> newSecurityLabels,
            String newDigest,
            String newPolicyVersion,
            MemoryRetentionPolicy newRetention,
            Optional<MemoryRef> replacement,
            Optional<MemoryRef> conflict,
            Optional<MemoryRef> reference,
            Optional<String> reason,
            Instant at,
            long newRevision) {
        return new MemoryCandidate(
                id,
                requestKey,
                scope,
                kind,
                newSubjectKey,
                newContent,
                newSources,
                newEvidence,
                newStatus,
                newSecurityLabels,
                newDigest,
                newPolicyVersion,
                newRetention,
                createdAt,
                at,
                newRevision,
                replacement,
                conflict,
                reference,
                reason);
    }
}
