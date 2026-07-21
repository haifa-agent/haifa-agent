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
        approvedMemory = Objects.requireNonNull(approvedMemory, "approvedMemory must not be null");
        dispositionReason = Objects.requireNonNull(dispositionReason, "dispositionReason must not be null");
        if (status == MemoryCandidateStatus.APPROVED && approvedMemory.isEmpty()) {
            throw new IllegalArgumentException("approved candidate requires memory reference");
        }
        if (status != MemoryCandidateStatus.APPROVED && approvedMemory.isPresent()) {
            throw new IllegalArgumentException("only approved candidate may reference memory");
        }
    }

    public MemoryCandidate approve(MemoryRef reference) {
        if (status == MemoryCandidateStatus.APPROVED) return this;
        if (status != MemoryCandidateStatus.PENDING) throw new IllegalStateException("candidate is not pending");
        return copy(MemoryCandidateStatus.APPROVED, Optional.of(reference), Optional.empty());
    }

    public MemoryCandidate reject(String reason) {
        if (status == MemoryCandidateStatus.REJECTED) return this;
        if (status != MemoryCandidateStatus.PENDING) throw new IllegalStateException("candidate is not pending");
        return copy(
                MemoryCandidateStatus.REJECTED,
                Optional.empty(),
                Optional.of(MemoryValues.text(reason, "reason", 512)));
    }

    public MemoryCandidate expire(String reason) {
        if (status == MemoryCandidateStatus.EXPIRED) return this;
        if (status != MemoryCandidateStatus.PENDING) throw new IllegalStateException("candidate is not pending");
        return copy(
                MemoryCandidateStatus.EXPIRED, Optional.empty(), Optional.of(MemoryValues.text(reason, "reason", 512)));
    }

    private MemoryCandidate copy(
            MemoryCandidateStatus newStatus, Optional<MemoryRef> reference, Optional<String> reason) {
        return new MemoryCandidate(
                id,
                requestKey,
                scope,
                kind,
                subjectKey,
                content,
                sources,
                evidence,
                newStatus,
                securityLabels,
                normalizedDigest,
                policyVersion,
                retention,
                createdAt,
                reference,
                reason);
    }
}
