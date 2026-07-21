package io.haifa.agent.context.item;

import java.util.Objects;

public record ContextProvenance(String sourceType, String sourceId, String sourceVersion, String contentHash) {
    public ContextProvenance {
        sourceType = requireText(sourceType, "sourceType");
        sourceId = requireText(sourceId, "sourceId");
        sourceVersion = requireText(sourceVersion, "sourceVersion");
        contentHash = requireText(contentHash, "contentHash");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
