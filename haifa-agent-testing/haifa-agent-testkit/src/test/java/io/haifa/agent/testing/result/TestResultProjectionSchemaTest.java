package io.haifa.agent.testing.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class TestResultProjectionSchemaTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void schemaTracksEveryBatchAndResultFieldAndCommonStatus() throws Exception {
        JsonNode schema = readSchema("test-result-projection.schema.json");

        assertEquals(
                1, schema.path("properties").path("schemaVersion").path("const").asInt());
        assertFalse(schema.path("additionalProperties").asBoolean());
        assertEquals(
                Set.of("schemaVersion", "projectionType", "suiteSystem", "suiteId", "generatedAt", "results"),
                values(schema.path("required")));
        assertEquals(
                Arrays.stream(TestResultProjection.class.getRecordComponents())
                        .map(component -> component.getName())
                        .collect(Collectors.toSet()),
                values(schema.path("$defs").path("result").path("required")));
        assertEquals(
                Arrays.stream(TestResultProjection.Status.values())
                        .map(Enum::name)
                        .collect(Collectors.toSet()),
                values(schema.path("$defs")
                        .path("result")
                        .path("properties")
                        .path("status")
                        .path("enum")));
        assertTrue(schema.path("$defs")
                .path("result")
                .path("properties")
                .path("evidenceRef")
                .path("pattern")
                .asText()
                .contains("[A-Za-z]:"));
    }

    @Test
    void legacyFunctionalResultSchemaIncludesDocumentedNotRunAndTimeoutStates() throws Exception {
        JsonNode schema = readSchema("test-result.schema.json");

        Set<String> statuses = values(schema.path("properties").path("status").path("enum"));
        assertTrue(statuses.containsAll(List.of("NOT_RUN", "TIMEOUT")));
        assertEquals(
                statuses,
                values(schema.path("properties").path("firstAttemptStatus").path("enum")));
    }

    private static Set<String> values(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.toSet());
    }

    private JsonNode readSchema(String name) throws Exception {
        String resource = "/io/haifa/agent/testing/result/" + name;
        try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream(resource), resource)) {
            return json.readTree(input);
        }
    }
}
