package io.haifa.agent.memory.api;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Bounded page of live memories in one scope, newest update first. {@code text} is an optional case-insensitive
 * substring match over subject key and content; it is not a semantic search.
 */
public record MemoryQuery(
        MemoryScope scope, Set<MemoryKind> kinds, Optional<String> text, Optional<MemoryPageCursor> after, int limit) {
    public static final int MAX_LIMIT = 1_000;

    public MemoryQuery {
        scope = Objects.requireNonNull(scope, "scope must not be null");
        kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds must not be null"));
        text = Objects.requireNonNull(text, "text must not be null")
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> MemoryValues.text(value, "text", 256));
        after = Objects.requireNonNull(after, "after must not be null");
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit must be between 1 and 1000");
    }

    public static MemoryQuery all(MemoryScope scope, int limit) {
        return new MemoryQuery(scope, Set.of(), Optional.empty(), Optional.empty(), limit);
    }

    /** Applies the kind and text filters to one live memory of this scope. */
    public boolean matches(Memory memory) {
        if (!kinds.isEmpty() && !kinds.contains(memory.kind())) return false;
        return text.map(value -> (memory.subjectKey() + "\n" + memory.content())
                        .toLowerCase(Locale.ROOT)
                        .contains(value.toLowerCase(Locale.ROOT)))
                .orElse(true);
    }
}
