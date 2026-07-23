package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class CodingAgentE2EFixtureTest {
    @Test
    void catalogContainsNineOfflineVersionedFixtures() throws Exception {
        JsonNode catalog;
        try (var input = CodingAgentE2EFixtureTest.class.getResourceAsStream("/coding-e2e/cases.yaml")) {
            catalog = new ObjectMapper(new YAMLFactory())
                    .readTree(Objects.requireNonNull(input, "missing coding E2E catalog"));
        }
        JsonNode cases = catalog.path("cases");
        assertThat(cases.isArray()).isTrue();
        assertThat(cases).hasSize(9);
        var ids = new HashSet<String>();
        for (JsonNode item : cases) {
            String caseId = item.path("caseId").asText();
            String fixture = item.path("fixture").asText();
            assertThat(caseId).matches("HF-06-E2E-CLI-00[1-9]");
            assertThat(ids.add(caseId)).isTrue();
            assertThat(item.path("caseVersion").asText()).isEqualTo("1.0");
            assertThat(item.path("task").asText())
                    .contains("do not use the network")
                    .doesNotContain("http://", "https://");
            assertThat(fixture).matches("[a-z0-9-]+");
            Path fixturePath = Path.of(Objects.requireNonNull(
                            CodingAgentE2EFixtureTest.class.getResource("/coding-e2e/fixtures/" + fixture))
                    .toURI());
            assertThat(Files.isDirectory(fixturePath)).isTrue();
            try (var files = Files.walk(fixturePath)) {
                assertThat(files.filter(Files::isRegularFile).count()).isPositive();
            }
        }
    }
}
