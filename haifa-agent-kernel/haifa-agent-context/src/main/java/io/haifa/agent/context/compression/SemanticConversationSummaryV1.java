package io.haifa.agent.context.compression;

import java.util.List;
import java.util.Objects;

/**
 * Versioned, normalized semantic conversation summary compiled from session history.
 * Derived data only; authoritative source messages and domain state remain primary.
 */
public record SemanticConversationSummaryV1(
        String schemaVersion,
        String language,
        List<SemanticSummaryItem> goals,
        List<SemanticSummaryItem> constraints,
        SemanticProgress progress,
        List<SemanticDecisionItem> decisions,
        List<SemanticSummaryItem> nextSteps,
        List<SemanticSummaryItem> criticalContext,
        List<SemanticSummaryItem> unresolvedQuestions) {

    public static final String CURRENT_SCHEMA_VERSION = "v1";

    public SemanticConversationSummaryV1 {
        schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion must not be null").trim();
        if (schemaVersion.isEmpty()) {
            throw new IllegalArgumentException("schemaVersion must not be blank");
        }
        language = Objects.requireNonNull(language, "language must not be null").trim();
        if (language.isEmpty()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        goals = List.copyOf(Objects.requireNonNull(goals, "goals must not be null"));
        constraints = List.copyOf(Objects.requireNonNull(constraints, "constraints must not be null"));
        progress = Objects.requireNonNull(progress, "progress must not be null");
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions must not be null"));
        nextSteps = List.copyOf(Objects.requireNonNull(nextSteps, "nextSteps must not be null"));
        criticalContext = List.copyOf(Objects.requireNonNull(criticalContext, "criticalContext must not be null"));
        unresolvedQuestions = List.copyOf(Objects.requireNonNull(unresolvedQuestions, "unresolvedQuestions must not be null"));
    }

    public boolean hasContent() {
        return !goals.isEmpty()
                || !constraints.isEmpty()
                || !progress.completed().isEmpty()
                || !progress.active().isEmpty()
                || !progress.blocked().isEmpty()
                || !decisions.isEmpty()
                || !nextSteps.isEmpty()
                || !criticalContext.isEmpty()
                || !unresolvedQuestions.isEmpty();
    }

    public List<SemanticSummaryItem> mandatoryCarryForwardItems() {
        List<SemanticSummaryItem> items = new java.util.ArrayList<>();
        items.addAll(constraints);
        items.addAll(progress.active());
        items.addAll(progress.blocked());
        items.addAll(unresolvedQuestions);
        return List.copyOf(items);
    }

    @SuppressWarnings("unchecked")
    public static SemanticConversationSummaryV1 fromMap(java.util.Map<String, Object> map) {
        Objects.requireNonNull(map, "map must not be null");
        String schema = Objects.toString(map.getOrDefault("schemaVersion", CURRENT_SCHEMA_VERSION), CURRENT_SCHEMA_VERSION).trim();
        String lang = Objects.toString(map.getOrDefault("language", "en"), "en").trim();

        List<SemanticSummaryItem> goals = mapSummaryItems(map.get("goals"));
        List<SemanticSummaryItem> constraints = mapSummaryItems(map.get("constraints"));

        SemanticProgress prog = SemanticProgress.empty();
        Object progressObj = map.get("progress");
        if (progressObj instanceof java.util.Map<?, ?> pMap) {
            prog = new SemanticProgress(
                    mapSummaryItems(pMap.get("completed")),
                    mapSummaryItems(pMap.get("active")),
                    mapSummaryItems(pMap.get("blocked")));
        }

        List<SemanticDecisionItem> decisions = mapDecisionItems(map.get("decisions"));
        List<SemanticSummaryItem> nextSteps = mapSummaryItems(map.get("nextSteps"));
        List<SemanticSummaryItem> criticalContext = mapSummaryItems(map.get("criticalContext"));
        List<SemanticSummaryItem> unresolvedQuestions = mapSummaryItems(map.get("unresolvedQuestions"));

        return new SemanticConversationSummaryV1(
                schema,
                lang,
                goals,
                constraints,
                prog,
                decisions,
                nextSteps,
                criticalContext,
                unresolvedQuestions);
    }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("schemaVersion", schemaVersion);
        map.put("language", language);
        map.put("goals", goals.stream().map(SemanticSummaryItem::toMap).toList());
        map.put("constraints", constraints.stream().map(SemanticSummaryItem::toMap).toList());
        map.put("progress", java.util.Map.of(
                "completed", progress.completed().stream().map(SemanticSummaryItem::toMap).toList(),
                "active", progress.active().stream().map(SemanticSummaryItem::toMap).toList(),
                "blocked", progress.blocked().stream().map(SemanticSummaryItem::toMap).toList()));
        map.put("decisions", decisions.stream().map(SemanticDecisionItem::toMap).toList());
        map.put("nextSteps", nextSteps.stream().map(SemanticSummaryItem::toMap).toList());
        map.put("criticalContext", criticalContext.stream().map(SemanticSummaryItem::toMap).toList());
        map.put("unresolvedQuestions", unresolvedQuestions.stream().map(SemanticSummaryItem::toMap).toList());
        return map;
    }

    private static List<SemanticSummaryItem> mapSummaryItems(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream()
                    .filter(java.util.Map.class::isInstance)
                    .map(item -> (java.util.Map<String, Object>) item)
                    .map(SemanticSummaryItem::fromMap)
                    .toList();
        }
        return List.of();
    }

    private static List<SemanticDecisionItem> mapDecisionItems(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream()
                    .filter(java.util.Map.class::isInstance)
                    .map(item -> (java.util.Map<String, Object>) item)
                    .map(SemanticDecisionItem::fromMap)
                    .toList();
        }
        return List.of();
    }
}
