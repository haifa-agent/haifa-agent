package io.haifa.agent.personalassistant.application.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.personalassistant.application.mission.MissionException;
import io.haifa.agent.personalassistant.application.mission.MissionTaskRunInput;
import java.util.List;

/** Produces a valid, bounded model projection from frozen structured dependency results. */
final class MissionDependencyContextProjector {
    static final int MAX_CONTEXT_CHARACTERS = 48_000;
    private static final int MAX_SOURCES_PER_DEPENDENCY = 6;
    private static final int MAX_CLAIMS_PER_DEPENDENCY = 6;
    private static final int MAX_UNRESOLVED_PER_DEPENDENCY = 5;
    private static final ObjectMapper JSON = new ObjectMapper();

    private MissionDependencyContextProjector() {}

    static String project(List<MissionTaskRunInput.DependencyResult> dependencies) {
        if (dependencies.isEmpty())
            return "{\"schemaVersion\":\"pa.mission-dependency-context/v1\",\"dependencies\":[]}";
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", "pa.mission-dependency-context/v1");
        ArrayNode projected = root.putArray("dependencies");
        int briefLimit = Math.min(8_000, Math.max(2_000, 24_000 / dependencies.size()));
        for (MissionTaskRunInput.DependencyResult dependency : dependencies) {
            JsonNode source = parse(dependency);
            ObjectNode target = projected.addObject();
            identity(target, dependency);
            target.put("brief", bounded(source.path("brief").asText(), briefLimit));
            projectSources(source.path("sources"), target.putArray("sources"));
            projectClaims(source.path("claims"), target.putArray("claims"));
            projectArtifactRefs(source.path("artifactRefs"), target.putArray("artifactRefs"));
            projectTextArray(
                    source.path("unresolvedQuestions"),
                    target.putArray("unresolvedQuestions"),
                    MAX_UNRESOLVED_PER_DEPENDENCY,
                    500);
        }
        String result = encode(root);
        return result.length() <= MAX_CONTEXT_CHARACTERS ? result : fallback(dependencies);
    }

    private static void projectSources(JsonNode values, ArrayNode target) {
        if (!values.isArray()) return;
        int count = 0;
        for (JsonNode value : values) {
            if (count++ >= MAX_SOURCES_PER_DEPENDENCY) break;
            ObjectNode source = target.addObject();
            copyText(value, source, "sourceId", 128);
            String locator = value.path("normalizedLocator").asText();
            if (locator.isBlank()) locator = value.path("locator").asText();
            source.put("locator", bounded(locator, 768));
            copyText(value, source, "title", 256);
            copyText(value, source, "status", 32);
            copyText(value, source, "publishedAt", 64);
        }
    }

    private static void projectClaims(JsonNode values, ArrayNode target) {
        if (!values.isArray()) return;
        int count = 0;
        for (JsonNode value : values) {
            if (count++ >= MAX_CLAIMS_PER_DEPENDENCY) break;
            ObjectNode claim = target.addObject();
            copyText(value, claim, "claimId", 128);
            copyText(value, claim, "claim", 800);
            projectTextArray(value.path("supportingSourceIds"), claim.putArray("supportingSourceIds"), 8, 128);
            projectTextArray(value.path("opposingSourceIds"), claim.putArray("opposingSourceIds"), 8, 128);
            copyText(value, claim, "limitations", 300);
            claim.put("unverified", value.path("unverified").asBoolean(true));
        }
    }

    private static void projectArtifactRefs(JsonNode values, ArrayNode target) {
        if (!values.isArray()) return;
        int count = 0;
        for (JsonNode value : values) {
            if (count++ >= 8) break;
            ObjectNode artifact = target.addObject();
            copyFirstText(value, artifact, "artifactId", List.of("artifactId", "id", "ref"), 256);
            copyText(value, artifact, "contentDigest", 128);
            copyText(value, artifact, "mediaType", 128);
            copyText(value, artifact, "title", 256);
        }
    }

    private static void projectTextArray(JsonNode values, ArrayNode target, int maximumItems, int maximumCharacters) {
        if (!values.isArray()) return;
        int count = 0;
        for (JsonNode value : values) {
            if (count++ >= maximumItems) break;
            if (value.isTextual()) target.add(bounded(value.asText(), maximumCharacters));
        }
    }

    private static String fallback(List<MissionTaskRunInput.DependencyResult> dependencies) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", "pa.mission-dependency-context/v1");
        root.put("projection", "SUMMARY_ONLY_CONTEXT_LIMIT");
        ArrayNode projected = root.putArray("dependencies");
        for (MissionTaskRunInput.DependencyResult dependency : dependencies) {
            JsonNode source = parse(dependency);
            ObjectNode target = projected.addObject();
            identity(target, dependency);
            target.put("brief", bounded(source.path("brief").asText(), 2_000));
            projectTextArray(source.path("unresolvedQuestions"), target.putArray("unresolvedQuestions"), 3, 300);
        }
        String result = encode(root);
        if (result.length() > MAX_CONTEXT_CHARACTERS) {
            throw new MissionException(
                    "MISSION_DEPENDENCY_CONTEXT_TOO_LARGE", "Bounded Mission dependency context still exceeds limit");
        }
        return result;
    }

    private static JsonNode parse(MissionTaskRunInput.DependencyResult dependency) {
        if (!"pa.research-task-result".equals(dependency.resultSchemaId())) {
            ObjectNode value = JSON.createObjectNode();
            value.put("brief", dependency.resultJson());
            return value;
        }
        try {
            JsonNode value = JSON.readTree(dependency.resultJson());
            if (!value.isObject()) {
                throw new MissionException(
                        "MISSION_DEPENDENCY_RESULT_INVALID", "Completed dependency result must be a JSON object");
            }
            return value;
        } catch (MissionException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new MissionException(
                    "MISSION_DEPENDENCY_RESULT_INVALID", "Completed dependency result is not valid JSON", exception);
        }
    }

    private static void identity(ObjectNode target, MissionTaskRunInput.DependencyResult dependency) {
        target.put("taskId", dependency.taskId());
        target.put("resultSchemaId", dependency.resultSchemaId());
        target.put("resultSchemaVersion", dependency.resultSchemaVersion());
        target.put("resultDigest", dependency.resultDigest());
    }

    private static void copyText(JsonNode source, ObjectNode target, String field, int maximumCharacters) {
        if (source.path(field).isTextual())
            target.put(field, bounded(source.path(field).asText(), maximumCharacters));
    }

    private static void copyFirstText(
            JsonNode source, ObjectNode target, String targetField, List<String> sourceFields, int maximumCharacters) {
        for (String field : sourceFields) {
            if (source.path(field).isTextual()) {
                target.put(targetField, bounded(source.path(field).asText(), maximumCharacters));
                return;
            }
        }
    }

    private static String bounded(String value, int maximumCharacters) {
        if (value == null || value.length() <= maximumCharacters) return value == null ? "" : value;
        return value.substring(0, maximumCharacters) + "…";
    }

    private static String encode(JsonNode value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new MissionException(
                    "MISSION_CODEC_FAILED", "Mission dependency context cannot be encoded", exception);
        }
    }
}
