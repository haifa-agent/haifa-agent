package io.haifa.agent.memory.core;

import io.haifa.agent.memory.api.MemoryContent;
import io.haifa.agent.memory.api.MemoryEvidenceRef;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryRetentionPolicy;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemorySourceRef;
import java.util.List;
import java.util.Objects;

/** Verified fact observation accepted by the deterministic candidate extractor. */
public record MemoryObservation(
        String requestKey,
        MemoryScope scope,
        MemoryKind kind,
        String subjectKey,
        MemoryContent content,
        List<MemorySourceRef> sources,
        List<MemoryEvidenceRef> evidence,
        MemoryRetentionPolicy retention,
        boolean automaticApprovalRequested) {
    public MemoryObservation {
        requestKey = requireText(requestKey, "requestKey");
        scope = Objects.requireNonNull(scope);
        kind = Objects.requireNonNull(kind);
        subjectKey = requireText(subjectKey, "subjectKey");
        content = Objects.requireNonNull(content);
        sources = List.copyOf(Objects.requireNonNull(sources));
        evidence = List.copyOf(Objects.requireNonNull(evidence));
        retention = Objects.requireNonNull(retention);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
