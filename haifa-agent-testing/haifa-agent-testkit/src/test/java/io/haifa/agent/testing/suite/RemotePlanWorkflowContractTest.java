package io.haifa.agent.testing.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class RemotePlanWorkflowContractTest {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void manualDispatchRunsOnlyCrossPlatformNightlyAndReleasePlans() throws Exception {
        JsonNode workflow = yaml.readTree(findRepositoryRoot()
                .resolve(".github/workflows/dev-integration.yml")
                .toFile());
        JsonNode jobs = workflow.path("jobs");
        JsonNode remotePlan = jobs.path("remote-suite-governance-plan");

        assertEquals(
                "github.event_name == 'workflow_dispatch'",
                remotePlan.path("if").asText());
        assertEquals(
                Set.of(
                        "nightly-v1|ubuntu-latest|linux-deepseek-primary",
                        "nightly-v1|windows-latest|windows-deepseek-primary",
                        "release-v1|ubuntu-latest|linux-deepseek",
                        "release-v1|windows-latest|windows-deepseek"),
                matrixEntries(remotePlan));

        String remotePlanDefinition = remotePlan.toString();
        assertTrue(remotePlanDefinition.contains("HAIFA_TEST_CONFIG_SSH_KEY"));
        assertFalse(remotePlanDefinition.contains("HAIFA_TEST_CONFIG_TOKEN"));
        assertFalse(remotePlanDefinition.contains("--execute"));
        assertFalse(remotePlanDefinition.contains("DEEPSEEK_API_KEY"));
        assertFalse(remotePlanDefinition.contains("HAIFA_TEST_APPROVED_MAX_ESTIMATED_COST_USD"));
        assertFalse(remotePlanDefinition.contains("HAIFA_TEST_APPROVED_PLAN_SHA256"));

        assertTrue(jobs.path("suite-governance-plan")
                .path("if")
                .asText()
                .contains("github.event_name != 'workflow_dispatch'"));
        assertTrue(jobs.path("fast-cross-platform")
                .path("if")
                .asText()
                .contains("github.event_name != 'workflow_dispatch'"));
    }

    @Test
    void privateTestConfigCheckoutsUseOnlyTheReadOnlyDeployKey() throws Exception {
        Path repositoryRoot = findRepositoryRoot();
        for (String workflow : List.of("dev-integration.yml", "dev-nightly-live.yml", "main-release.yml")) {
            Path workflowPath = repositoryRoot.resolve(".github/workflows").resolve(workflow);
            assertTrue(yaml.readTree(workflowPath.toFile()).has("jobs"));
            String definition = Files.readString(workflowPath);
            assertTrue(definition.contains("ssh-key: ${{ secrets.HAIFA_TEST_CONFIG_SSH_KEY }}"));
            assertFalse(definition.contains("HAIFA_TEST_CONFIG_TOKEN"));
        }
    }

    private static Set<String> matrixEntries(JsonNode remotePlan) {
        return StreamSupport.stream(
                        remotePlan
                                .path("strategy")
                                .path("matrix")
                                .path("include")
                                .spliterator(),
                        false)
                .map(entry -> String.join(
                        "|",
                        entry.path("suite").asText(),
                        entry.path("os").asText(),
                        entry.path("combination").asText()))
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
