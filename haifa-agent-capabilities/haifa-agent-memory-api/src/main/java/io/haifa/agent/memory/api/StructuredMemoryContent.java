package io.haifa.agent.memory.api;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

public record StructuredMemoryContent(Map<String, String> values) implements MemoryContent {
    public StructuredMemoryContent {
        Objects.requireNonNull(values, "values must not be null");
        if (values.isEmpty() || values.size() > 32) throw new IllegalArgumentException("values size is invalid");
        TreeMap<String, String> normalized = new TreeMap<>();
        values.forEach((key, value) ->
                normalized.put(MemoryValues.text(key, "key", 128), MemoryValues.content(value, "value", 1024)));
        values = Map.copyOf(normalized);
        if (boundedText().length() > 4096) throw new IllegalArgumentException("structured content is too long");
    }

    @Override
    public String boundedText() {
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
    }

    @Override
    public int estimatedTokens() {
        return Math.max(1, (boundedText().length() + 3) / 4);
    }
}
