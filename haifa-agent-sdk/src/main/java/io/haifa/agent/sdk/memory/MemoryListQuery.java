package io.haifa.agent.sdk.memory;

import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryPageCursor;
import io.haifa.agent.memory.api.MemoryStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record MemoryListQuery(
        MemoryScopeSpec scope,
        Set<MemoryStatus> statuses,
        Set<MemoryKind> kinds,
        Optional<Instant> updatedBefore,
        Optional<MemoryPageCursor> after,
        int limit) {
    public MemoryListQuery {
        scope = Objects.requireNonNull(scope, "scope must not be null");
        statuses = Set.copyOf(Objects.requireNonNull(statuses, "statuses must not be null"));
        kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds must not be null"));
        updatedBefore = Objects.requireNonNull(updatedBefore, "updatedBefore must not be null");
        after = Objects.requireNonNull(after, "after must not be null");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
    }
}
