package io.haifa.agent.context.compression;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standard JSON schema definition for {@link SemanticConversationSummaryV1}.
 * Used to declare structured output requirements for the summarization model call.
 */
public final class SemanticSummarySchema {

    private SemanticSummarySchema() {}

    public static Map<String, Object> jsonSchema() {
        Map<String, Object> summaryItemProperties = new LinkedHashMap<>();
        summaryItemProperties.put("stableItemId", Map.of("type", "string"));
        summaryItemProperties.put("text", Map.of("type", "string"));
        summaryItemProperties.put("sourceRefs", Map.of("type", "array", "items", Map.of("type", "string")));
        summaryItemProperties.put("confidence", Map.of("type", "string", "enum", List.of("OBSERVED", "INFERRED")));

        Map<String, Object> summaryItemDef = new LinkedHashMap<>();
        summaryItemDef.put("type", "object");
        summaryItemDef.put("properties", summaryItemProperties);
        summaryItemDef.put("required", List.of("stableItemId", "text", "sourceRefs", "confidence"));
        summaryItemDef.put("additionalProperties", false);

        Map<String, Object> progressProperties = new LinkedHashMap<>();
        progressProperties.put("completed", Map.of("type", "array", "items", Map.of("$ref", "#/$defs/SummaryItem")));
        progressProperties.put("active", Map.of("type", "array", "items", Map.of("$ref", "#/$defs/SummaryItem")));
        progressProperties.put("blocked", Map.of("type", "array", "items", Map.of("$ref", "#/$defs/SummaryItem")));

        Map<String, Object> progressSchema = new LinkedHashMap<>();
        progressSchema.put("type", "object");
        progressSchema.put("properties", progressProperties);
        progressSchema.put("required", List.of("completed", "active", "blocked"));
        progressSchema.put("additionalProperties", false);

        Map<String, Object> decisionProperties = new LinkedHashMap<>();
        decisionProperties.put("stableItemId", Map.of("type", "string"));
        decisionProperties.put("statement", Map.of("type", "string"));
        decisionProperties.put("rationale", Map.of("type", "string"));
        decisionProperties.put(
                "status", Map.of("type", "string", "enum", List.of("PROPOSED", "ACCEPTED", "SUPERSEDED", "REJECTED")));
        decisionProperties.put("sourceRefs", Map.of("type", "array", "items", Map.of("type", "string")));

        Map<String, Object> decisionSchema = new LinkedHashMap<>();
        decisionSchema.put("type", "object");
        decisionSchema.put("properties", decisionProperties);
        decisionSchema.put("required", List.of("stableItemId", "statement", "rationale", "status", "sourceRefs"));
        decisionSchema.put("additionalProperties", false);

        Map<String, Object> rootProperties = new LinkedHashMap<>();
        rootProperties.put("schemaVersion", Map.of("type", "string"));
        rootProperties.put("language", Map.of("type", "string"));
        rootProperties.put("goals", Map.of("type", "array", "items", Map.of("$ref", "#/$defs/SummaryItem")));
        rootProperties.put("constraints", Map.of("type", "array", "items", Map.of("$ref", "#/$defs/SummaryItem")));
        rootProperties.put("progress", progressSchema);
        rootProperties.put("decisions", Map.of("type", "array", "items", decisionSchema));
        rootProperties.put("nextSteps", Map.of("type", "array", "items", Map.of("$ref", "#/$defs/SummaryItem")));
        rootProperties.put("criticalContext", Map.of("type", "array", "items", Map.of("$ref", "#/$defs/SummaryItem")));
        rootProperties.put(
                "unresolvedQuestions", Map.of("type", "array", "items", Map.of("$ref", "#/$defs/SummaryItem")));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", rootProperties);
        root.put(
                "required",
                List.of(
                        "schemaVersion",
                        "language",
                        "goals",
                        "constraints",
                        "progress",
                        "decisions",
                        "nextSteps",
                        "criticalContext",
                        "unresolvedQuestions"));
        root.put("additionalProperties", false);
        root.put("$defs", Map.of("SummaryItem", summaryItemDef));

        return Map.copyOf(root);
    }
}
