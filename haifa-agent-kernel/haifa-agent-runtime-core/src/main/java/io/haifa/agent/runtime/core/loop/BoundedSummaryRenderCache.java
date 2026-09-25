package io.haifa.agent.runtime.core.loop;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Small process-local LRU for derived summary markdown; authoritative summaries remain in the repository. */
final class BoundedSummaryRenderCache<K> {
    private final int maximumEntries;
    private final long maximumCharacters;
    private final Map<K, String> values = new LinkedHashMap<>(16, 0.75f, true);
    private long characters;

    BoundedSummaryRenderCache(int maximumEntries, long maximumCharacters) {
        if (maximumEntries < 1) throw new IllegalArgumentException("maximumEntries must be positive");
        if (maximumCharacters < 1) throw new IllegalArgumentException("maximumCharacters must be positive");
        this.maximumEntries = maximumEntries;
        this.maximumCharacters = maximumCharacters;
    }

    synchronized Optional<String> get(K key) {
        return Optional.ofNullable(values.get(Objects.requireNonNull(key, "key must not be null")));
    }

    synchronized void put(K key, String value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        String previous = values.remove(key);
        if (previous != null) characters -= previous.length();
        if (value.length() > maximumCharacters) return;
        values.put(key, value);
        characters += value.length();
        evictIfNeeded();
    }

    synchronized int size() {
        return values.size();
    }

    synchronized long characters() {
        return characters;
    }

    private void evictIfNeeded() {
        var iterator = values.entrySet().iterator();
        while ((values.size() > maximumEntries || characters > maximumCharacters) && iterator.hasNext()) {
            Map.Entry<K, String> eldest = iterator.next();
            characters -= eldest.getValue().length();
            iterator.remove();
        }
    }
}
