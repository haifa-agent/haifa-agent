package io.haifa.agent.testing.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class TestResultProjectionSchemaTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void schemaTracksEveryBatchAndResultFieldAndCommonStatus() throws Exception {
        JsonNode schema = json.readTree(findRepositoryRoot()
                .resolve("docs/testing/schemas/test-result-projection.schema.json")
                .toFile());

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
        JsonNode schema = json.readTree(findRepositoryRoot()
                .resolve("docs/testing/schemas/test-result.schema.json")
                .toFile());

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

    private static Path findRepositoryRoot() {
        Path current =
                Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".mvn")) && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate repository root from Maven basedir");
    }
}
