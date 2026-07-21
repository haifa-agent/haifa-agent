package io.haifa.agent.context.item;

import java.util.Objects;

/** Phase-three extension point without coupling the Context module to a Memory implementation. */
public record MemoryReferenceContent(String memoryId, String version, String text) implements ContextContent {
    public MemoryReferenceContent {
        memoryId = requireText(memoryId, "memoryId");
        version = requireText(version, "version");
        text = requireText(text, "text");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
