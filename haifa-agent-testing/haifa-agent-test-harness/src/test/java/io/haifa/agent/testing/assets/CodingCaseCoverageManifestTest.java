package io.haifa.agent.testing.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.haifa.agent.testing.suite.CriticalPathCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class CodingCaseCoverageManifestTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void coversEveryCurrentCaseSetAndRetainsLegacySemanticsUntilMigration() throws Exception {
        Path repositoryRoot = findRepositoryRoot();
        JsonNode manifest = json.readTree(repositoryRoot
                .resolve("haifa-agent-testing/coding-case-coverage-v1.json")
                .toFile());
        JsonNode legacy = yaml.readTree(repositoryRoot
                .resolve("haifa-agent-testing/haifa-agent-e2e-tests/src/test/resources/coding-e2e/cases.yaml")
                .toFile());
        JsonNode autonomous = json.readTree(repositoryRoot
                .resolve(
                        "haifa-agent-testing/haifa-agent-test-fixtures/src/main/resources/fixtures/autonomous-delivery/catalog-v1.json")
                .toFile());

        assertEquals(1, manifest.path("schemaVersion").asInt());
        Set<String> actualCriticalPath = CriticalPathCatalog.cases().stream()
                .map(value -> value.caseId())
                .filter(Set.of("CP-02", "CP-03", "CP-04", "CP-05", "CP-06")::contains)
                .collect(Collectors.toSet());
        Set<String> actualLegacy = values(legacy.path("cases"), "caseId");
        Set<String> actualAutonomous = values(autonomous.path("cases"), "caseId");

        assertEquals(actualCriticalPath, values(manifest.path("caseSets").path("criticalPathCoding")));
        assertEquals(actualLegacy, values(manifest.path("caseSets").path("legacyCodingLive")));
        assertEquals(actualAutonomous, values(manifest.path("caseSets").path("autonomousDelivery")));

        JsonNode crosswalk = manifest.path("crosswalk");
        assertTrue(crosswalk.isArray());
        assertEquals(actualLegacy, values(crosswalk, "sourceCaseId"));
        for (JsonNode mapping : crosswalk) {
            assertEquals(
                    "RETAIN_UNTIL_MIGRATED", mapping.path("retirementDecision").asText());
            assertTrue(
                    actualCriticalPath.containsAll(values(mapping.path("criticalPathCases"))),
                    () -> "unknown Critical Path mapping for "
                            + mapping.path("sourceCaseId").asText());
            assertTrue(
                    actualAutonomous.containsAll(values(mapping.path("autonomousDeliveryAnalogues"))),
                    () -> "unknown Autonomous Delivery mapping for "
                            + mapping.path("sourceCaseId").asText());
            assertTrue(mapping.path("uniqueAssertions").isArray());
            assertTrue(mapping.path("uniqueAssertions").size() > 0);
        }
    }

    private static Set<String> values(JsonNode array) {
        Set<String> result = new HashSet<>();
        StreamSupport.stream(array.spliterator(), false).forEach(value -> result.add(value.asText()));
        return result;
    }

    private static Set<String> values(JsonNode array, String field) {
        Set<String> result = new HashSet<>();
        StreamSupport.stream(array.spliterator(), false)
                .forEach(value -> result.add(value.path(field).asText()));
        return result;
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
