package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
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
            String expectedVersion = caseId.equals("HF-06-E2E-CLI-009") ? "1.0" : "2.0";
            assertThat(item.path("caseVersion").asText()).isEqualTo(expectedVersion);
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
            if (!caseId.equals("HF-06-E2E-CLI-009")) {
                assertThat(fixturePath.resolve("verify.json")).isRegularFile();
                assertThat(fixturePath.resolve("verify.sh")).isRegularFile();
                assertThat(fixturePath.resolve("verify.ps1")).isRegularFile();
            }
        }
        assertThat(CodingAgentE2EFixtureTest.class.getResource("/coding-e2e/support/verify_java.py"))
                .isNotNull();
    }

    @Test
    void liveExecutionRequiresAnExplicitTrustedWindowsHostPair() {
        assertThat(CodingAgentLiveE2E.liveExecution(Map.of()))
                .extracting(CliConfiguration.Execution::provider, CliConfiguration.Execution::network)
                .containsExactly("local-native", "deny");

        assertThat(CodingAgentLiveE2E.liveExecution(Map.of(
                        "HAIFA_CLI_LIVE_E2E_EXECUTION_PROVIDER",
                        "host-guarded",
                        "HAIFA_CLI_LIVE_E2E_EXECUTION_NETWORK",
                        "allow")))
                .extracting(CliConfiguration.Execution::provider, CliConfiguration.Execution::network)
                .containsExactly("host-guarded", "allow");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CodingAgentLiveE2E.liveExecution(
                        Map.of("HAIFA_CLI_LIVE_E2E_EXECUTION_PROVIDER", "host-guarded")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be set together");
    }
}
