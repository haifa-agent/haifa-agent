package io.haifa.agent.context.compression;

import java.util.List;
import java.util.Objects;

/** A decision item tracked in the semantic summary with its rationale and status. */
public record SemanticDecisionItem(
        String stableItemId,
        String statement,
        String rationale,
        SemanticDecisionStatus status,
        List<String> sourceRefs) {
    public SemanticDecisionItem {
        stableItemId = Objects.requireNonNull(stableItemId, "stableItemId must not be null")
                .trim();
        if (stableItemId.isEmpty()) {
            throw new IllegalArgumentException("stableItemId must not be blank");
        }
        statement =
                Objects.requireNonNull(statement, "statement must not be null").trim();
        if (statement.isEmpty()) {
            throw new IllegalArgumentException("statement must not be blank");
        }
        rationale = Objects.requireNonNullElse(rationale, "").trim();
        status = Objects.requireNonNull(status, "status must not be null");
        sourceRefs = List.copyOf(Objects.requireNonNull(sourceRefs, "sourceRefs must not be null"));
    }

    public static SemanticDecisionItem fromMap(java.util.Map<String, Object> map) {
        Objects.requireNonNull(map, "map must not be null");
        String stableItemId =
                Objects.toString(map.getOrDefault("stableItemId", ""), "").trim();
        String statement =
                Objects.toString(map.getOrDefault("statement", ""), "").trim();
        String rationale =
                Objects.toString(map.getOrDefault("rationale", ""), "").trim();
        Object statusObj = map.get("status");
        if (statusObj == null || statusObj.toString().isBlank()) {
            throw new IllegalArgumentException("status must not be null or blank in SemanticDecisionItem");
        }
        SemanticDecisionStatus status;
        try {
            status = SemanticDecisionStatus.valueOf(statusObj.toString().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown decision status value: " + statusObj, e);
        }
        List<String> sourceRefs = extractStringList(map.get("sourceRefs"));
        return new SemanticDecisionItem(stableItemId, statement, rationale, status, sourceRefs);
    }

    public java.util.Map<String, Object> toMap() {
        return java.util.Map.of(
                "stableItemId", stableItemId,
                "statement", statement,
                "rationale", rationale,
                "status", status.name(),
                "sourceRefs", sourceRefs);
    }

    private static List<String> extractStringList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        return List.of();
    }
}
