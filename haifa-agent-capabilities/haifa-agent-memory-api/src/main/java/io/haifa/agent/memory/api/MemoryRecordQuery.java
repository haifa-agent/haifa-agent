package io.haifa.agent.memory.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record MemoryRecordQuery(
        MemoryScope scope,
        Set<MemoryStatus> statuses,
        Set<MemoryKind> kinds,
        Optional<Instant> updatedBefore,
        Optional<MemoryPageCursor> after,
        int limit) {
    public MemoryRecordQuery {
        scope = Objects.requireNonNull(scope, "scope must not be null");
        statuses = Set.copyOf(Objects.requireNonNull(statuses, "statuses must not be null"));
        kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds must not be null"));
        updatedBefore = Objects.requireNonNull(updatedBefore, "updatedBefore must not be null");
        after = Objects.requireNonNull(after, "after must not be null");
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("limit must be between 1 and 1000");
    }
}
