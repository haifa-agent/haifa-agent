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
        schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion must not be null")
                .trim();
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
        unresolvedQuestions =
                List.copyOf(Objects.requireNonNull(unresolvedQuestions, "unresolvedQuestions must not be null"));
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
        Object schemaObj = map.get("schemaVersion");
        if (schemaObj == null || schemaObj.toString().isBlank()) {
            throw new IllegalArgumentException("schemaVersion must not be null or blank");
        }
        String schema = schemaObj.toString().trim();

        Object langObj = map.get("language");
        if (langObj == null || langObj.toString().isBlank()) {
            throw new IllegalArgumentException("language must not be null or blank");
        }
        String lang = langObj.toString().trim();

        List<SemanticSummaryItem> goals = requireSummaryItems(map, "goals");
        List<SemanticSummaryItem> constraints = requireSummaryItems(map, "constraints");

        Object progressObj = map.get("progress");
        if (!(progressObj instanceof java.util.Map<?, ?> pMap)) {
            throw new IllegalArgumentException("progress must be a non-null object in SemanticConversationSummaryV1");
        }
        SemanticProgress prog = new SemanticProgress(
                requireSummaryItems((java.util.Map<String, Object>) pMap, "completed"),
                requireSummaryItems((java.util.Map<String, Object>) pMap, "active"),
                requireSummaryItems((java.util.Map<String, Object>) pMap, "blocked"));

        List<SemanticDecisionItem> decisions = requireDecisionItems(map, "decisions");
        List<SemanticSummaryItem> nextSteps = requireSummaryItems(map, "nextSteps");
        List<SemanticSummaryItem> criticalContext = requireSummaryItems(map, "criticalContext");
        List<SemanticSummaryItem> unresolvedQuestions = requireSummaryItems(map, "unresolvedQuestions");

        return new SemanticConversationSummaryV1(
                schema, lang, goals, constraints, prog, decisions, nextSteps, criticalContext, unresolvedQuestions);
    }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("schemaVersion", schemaVersion);
        map.put("language", language);
        map.put("goals", goals.stream().map(SemanticSummaryItem::toMap).toList());
        map.put(
                "constraints",
                constraints.stream().map(SemanticSummaryItem::toMap).toList());
        map.put(
                "progress",
                java.util.Map.of(
                        "completed",
                                progress.completed().stream()
                                        .map(SemanticSummaryItem::toMap)
                                        .toList(),
                        "active",
                                progress.active().stream()
                                        .map(SemanticSummaryItem::toMap)
                                        .toList(),
                        "blocked",
                                progress.blocked().stream()
                                        .map(SemanticSummaryItem::toMap)
                                        .toList()));
        map.put("decisions", decisions.stream().map(SemanticDecisionItem::toMap).toList());
        map.put("nextSteps", nextSteps.stream().map(SemanticSummaryItem::toMap).toList());
        map.put(
                "criticalContext",
                criticalContext.stream().map(SemanticSummaryItem::toMap).toList());
        map.put(
                "unresolvedQuestions",
                unresolvedQuestions.stream().map(SemanticSummaryItem::toMap).toList());
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<SemanticSummaryItem> requireSummaryItems(java.util.Map<String, Object> map, String key) {
        if (!map.containsKey(key)) {
            throw new IllegalArgumentException("missing required field '" + key + "' in SemanticConversationSummaryV1");
        }
        Object obj = map.get(key);
        if (!(obj instanceof List<?> list)) {
            throw new IllegalArgumentException("field '" + key + "' must be a list in SemanticConversationSummaryV1");
        }
        List<SemanticSummaryItem> items = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof java.util.Map<?, ?> itemMap)) {
                throw new IllegalArgumentException("items in '" + key + "' must be objects");
            }
            items.add(SemanticSummaryItem.fromMap((java.util.Map<String, Object>) itemMap));
        }
        return List.copyOf(items);
    }

    @SuppressWarnings("unchecked")
    private static List<SemanticDecisionItem> requireDecisionItems(java.util.Map<String, Object> map, String key) {
        if (!map.containsKey(key)) {
            throw new IllegalArgumentException("missing required field '" + key + "' in SemanticConversationSummaryV1");
        }
        Object obj = map.get(key);
        if (!(obj instanceof List<?> list)) {
            throw new IllegalArgumentException("field '" + key + "' must be a list in SemanticConversationSummaryV1");
        }
        List<SemanticDecisionItem> items = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof java.util.Map<?, ?> itemMap)) {
                throw new IllegalArgumentException("items in '" + key + "' must be objects");
            }
            items.add(SemanticDecisionItem.fromMap((java.util.Map<String, Object>) itemMap));
        }
        return List.copyOf(items);
    }
}
