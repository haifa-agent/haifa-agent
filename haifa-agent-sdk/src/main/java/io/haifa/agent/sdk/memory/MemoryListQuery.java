package io.haifa.agent.sdk.memory;

import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryPageCursor;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Bounded page of live memories in one bucket; {@code text} is a case-insensitive substring match. */
public record MemoryListQuery(
        MemoryScopeSpec scope,
        Set<MemoryKind> kinds,
        Optional<String> text,
        Optional<MemoryPageCursor> after,
        int limit) {
    public MemoryListQuery {
        scope = Objects.requireNonNull(scope, "scope must not be null");
        kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds must not be null"));
        text = Objects.requireNonNull(text, "text must not be null");
        after = Objects.requireNonNull(after, "after must not be null");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
    }

    public static MemoryListQuery of(MemoryScopeSpec scope, int limit) {
        return new MemoryListQuery(scope, Set.of(), Optional.empty(), Optional.empty(), limit);
    }
}
