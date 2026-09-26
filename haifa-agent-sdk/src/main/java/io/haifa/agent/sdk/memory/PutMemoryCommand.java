package io.haifa.agent.sdk.memory;

import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemorySourceRef;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit or captured Memory write. {@code source} is optional provenance; {@code observedAt} is the time the
 * source facts were observed and must be set by asynchronous capture so a write that started before a clear or
 * delete cannot resurrect the content.
 */
public record PutMemoryCommand(
        MemoryScopeSpec scope,
        MemoryKind kind,
        String subjectKey,
        String content,
        Optional<MemorySourceRef> source,
        Optional<Instant> observedAt) {
    public PutMemoryCommand {
        scope = Objects.requireNonNull(scope, "scope must not be null");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        subjectKey = MemoryScopeSpec.requireText(subjectKey, 256);
        content = Objects.requireNonNull(content, "content must not be null");
        source = Objects.requireNonNull(source, "source must not be null");
        observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
    }

    public static PutMemoryCommand of(MemoryScopeSpec scope, MemoryKind kind, String subjectKey, String content) {
        return new PutMemoryCommand(scope, kind, subjectKey, content, Optional.empty(), Optional.empty());
    }
}
