package io.haifa.agent.memory.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record Memory(
        MemoryId id,
        MemoryVersion version,
        MemoryScope scope,
        MemoryKind kind,
        String subjectKey,
        Optional<MemoryContent> content,
        List<MemorySourceRef> sources,
        List<MemoryEvidenceRef> evidence,
        MemoryStatus status,
        Set<MemorySecurityLabel> securityLabels,
        String normalizedDigest,
        Optional<MemoryRef> previousVersion,
        Optional<String> invalidationReason,
        Optional<MemoryRef> replacedByMemoryRef,
        MemoryRetentionPolicy retention,
        Instant createdAt,
        Instant updatedAt) {
    public Memory {
        id = Objects.requireNonNull(id, "id must not be null");
        version = Objects.requireNonNull(version, "version must not be null");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        subjectKey = MemoryValues.text(subjectKey, "subjectKey", 256);
        content = Objects.requireNonNull(content, "content must not be null");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
        status = Objects.requireNonNull(status, "status must not be null");
        securityLabels = Set.copyOf(Objects.requireNonNull(securityLabels, "securityLabels must not be null"));
        normalizedDigest = MemoryValues.text(normalizedDigest, "normalizedDigest", 128);
        previousVersion = Objects.requireNonNull(previousVersion, "previousVersion must not be null");
        invalidationReason = Objects.requireNonNull(invalidationReason, "invalidationReason must not be null")
                .map(value -> MemoryValues.text(value, "invalidationReason", 128));
        replacedByMemoryRef = Objects.requireNonNull(replacedByMemoryRef, "replacedByMemoryRef must not be null");
        retention = Objects.requireNonNull(retention, "retention must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (status == MemoryStatus.PURGED && content.isPresent()) {
            throw new IllegalArgumentException("purged memory cannot retain content");
        }
        if (status != MemoryStatus.PURGED && content.isEmpty()) {
            throw new IllegalArgumentException("non-purged memory requires content");
        }
        if (replacedByMemoryRef.isPresent()
                && (status != MemoryStatus.INVALIDATED
                        || !invalidationReason.filter("REPLACED"::equals).isPresent())) {
            throw new IllegalArgumentException("replacement reference requires INVALIDATED reason REPLACED");
        }
    }

    public Memory(
            MemoryId id,
            MemoryVersion version,
            MemoryScope scope,
            MemoryKind kind,
            String subjectKey,
            Optional<MemoryContent> content,
            List<MemorySourceRef> sources,
            List<MemoryEvidenceRef> evidence,
            MemoryStatus status,
            Set<MemorySecurityLabel> securityLabels,
            String normalizedDigest,
            Optional<MemoryRef> previousVersion,
            MemoryRetentionPolicy retention,
            Instant createdAt,
            Instant updatedAt) {
        this(
                id,
                version,
                scope,
                kind,
                subjectKey,
                content,
                sources,
                evidence,
                status,
                securityLabels,
                normalizedDigest,
                previousVersion,
                Optional.empty(),
                Optional.empty(),
                retention,
                createdAt,
                updatedAt);
    }

    public Memory transition(MemoryStatus target, Instant at) {
        Objects.requireNonNull(target);
        boolean allowed =
                switch (status) {
                    case ACTIVE ->
                        target == MemoryStatus.SUPERSEDED
                                || target == MemoryStatus.INVALIDATED
                                || target == MemoryStatus.EXPIRED
                                || target == MemoryStatus.PURGE_PENDING;
                    case SUPERSEDED, INVALIDATED, EXPIRED -> target == MemoryStatus.PURGE_PENDING;
                    case PURGE_PENDING -> target == MemoryStatus.PURGED;
                    case PURGED -> false;
                };
        if (!allowed) throw new IllegalStateException("invalid memory transition: " + status + " -> " + target);
        return new Memory(
                id,
                version,
                scope,
                kind,
                subjectKey,
                target == MemoryStatus.PURGED ? Optional.empty() : content,
                target == MemoryStatus.PURGED ? List.of() : sources,
                target == MemoryStatus.PURGED ? List.of() : evidence,
                target,
                target == MemoryStatus.PURGED ? Set.of() : securityLabels,
                normalizedDigest,
                previousVersion,
                target == MemoryStatus.INVALIDATED ? Optional.of("SOURCE_INVALIDATED") : invalidationReason,
                Optional.empty(),
                retention,
                createdAt,
                Objects.requireNonNull(at));
    }

    public Memory invalidate(String reason, Optional<MemoryRef> replacement, Instant at) {
        if (status == MemoryStatus.INVALIDATED) {
            if (invalidationReason.equals(Optional.of(MemoryValues.text(reason, "reason", 128)))
                    && replacedByMemoryRef.equals(replacement)) return this;
            throw new IllegalStateException("memory is already invalidated differently");
        }
        if (status != MemoryStatus.ACTIVE) throw new IllegalStateException("memory is not active");
        String safeReason = MemoryValues.text(reason, "reason", 128);
        if (replacement.isPresent() != "REPLACED".equals(safeReason)) {
            throw new IllegalArgumentException("replacement reference and REPLACED reason must be present together");
        }
        return new Memory(
                id,
                version,
                scope,
                kind,
                subjectKey,
                content,
                sources,
                evidence,
                MemoryStatus.INVALIDATED,
                securityLabels,
                normalizedDigest,
                previousVersion,
                Optional.of(safeReason),
                replacement,
                retention,
                createdAt,
                Objects.requireNonNull(at));
    }

    public boolean expiredAt(Instant now) {
        return retention.expiresAt().map(expires -> !expires.isAfter(now)).orElse(false);
    }
}
