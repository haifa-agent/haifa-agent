package io.haifa.agent.memory.api;

import java.util.List;
import java.util.Objects;

public record MemoryCandidateDraft(
        String requestKey,
        MemoryScope scope,
        MemoryKind kind,
        String subjectKey,
        MemoryContent content,
        List<MemorySourceRef> sources,
        List<MemoryEvidenceRef> evidence,
        MemoryRetentionPolicy retention,
        boolean automaticApprovalRequested) {
    public MemoryCandidateDraft {
        requestKey = MemoryValues.text(requestKey, "requestKey", 256);
        scope = Objects.requireNonNull(scope);
        kind = Objects.requireNonNull(kind);
        subjectKey = MemoryValues.text(subjectKey, "subjectKey", 256);
        content = Objects.requireNonNull(content);
        sources = List.copyOf(Objects.requireNonNull(sources));
        evidence = List.copyOf(Objects.requireNonNull(evidence));
        if (sources.isEmpty() || evidence.isEmpty()) throw new IllegalArgumentException("candidate requires evidence");
        retention = Objects.requireNonNull(retention);
    }
}
