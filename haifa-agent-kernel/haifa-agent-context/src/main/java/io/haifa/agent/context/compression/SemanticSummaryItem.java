package io.haifa.agent.context.compression;

import java.util.List;
import java.util.Objects;

/** A single structured item in a semantic conversation summary. */
public record SemanticSummaryItem(
        String stableItemId,
        String text,
        List<String> sourceRefs,
        SemanticConfidence confidence) {
    public SemanticSummaryItem {
        stableItemId = Objects.requireNonNull(stableItemId, "stableItemId must not be null").trim();
        if (stableItemId.isEmpty()) {
            throw new IllegalArgumentException("stableItemId must not be blank");
        }
        text = Objects.requireNonNull(text, "text must not be null").trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        sourceRefs = List.copyOf(Objects.requireNonNull(sourceRefs, "sourceRefs must not be null"));
        confidence = Objects.requireNonNull(confidence, "confidence must not be null");
    }

    public static SemanticSummaryItem fromMap(java.util.Map<String, Object> map) {
        Objects.requireNonNull(map, "map must not be null");
        String stableItemId = Objects.toString(map.getOrDefault("stableItemId", ""), "").trim();
        String text = Objects.toString(map.getOrDefault("text", ""), "").trim();
        List<String> sourceRefs = extractStringList(map.get("sourceRefs"));
        Object confObj = map.get("confidence");
        SemanticConfidence confidence = SemanticConfidence.OBSERVED;
        if (confObj != null) {
            try {
                confidence = SemanticConfidence.valueOf(confObj.toString().trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                confidence = SemanticConfidence.OBSERVED;
            }
        }
        return new SemanticSummaryItem(stableItemId, text, sourceRefs, confidence);
    }

    public java.util.Map<String, Object> toMap() {
        return java.util.Map.of(
                "stableItemId", stableItemId,
                "text", text,
                "sourceRefs", sourceRefs,
                "confidence", confidence.name());
    }

    private static List<String> extractStringList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        return List.of();
    }
}
