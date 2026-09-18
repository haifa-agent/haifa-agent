package io.haifa.agent.sdk.memory;

import io.haifa.agent.memory.api.MemoryContent;
import io.haifa.agent.memory.api.MemoryEvidenceRef;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryRef;
import io.haifa.agent.memory.api.MemorySourceRef;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ProposeMemoryCommand(
        String idempotencyKey,
        MemoryScopeSpec scope,
        MemoryKind kind,
        String subjectKey,
        MemoryContent content,
        List<MemorySourceRef> sources,
        List<MemoryEvidenceRef> evidence,
        Optional<MemoryRef> replacesMemoryRef) {
    public ProposeMemoryCommand {
        idempotencyKey = MemoryScopeSpec.requireText(idempotencyKey, 256);
        scope = Objects.requireNonNull(scope, "scope must not be null");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        subjectKey = MemoryScopeSpec.requireText(subjectKey, 256);
        content = Objects.requireNonNull(content, "content must not be null");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
        if (sources.isEmpty() || evidence.isEmpty()) throw new IllegalArgumentException("evidence is required");
        replacesMemoryRef = Objects.requireNonNull(replacesMemoryRef, "replacesMemoryRef must not be null");
    }
}
