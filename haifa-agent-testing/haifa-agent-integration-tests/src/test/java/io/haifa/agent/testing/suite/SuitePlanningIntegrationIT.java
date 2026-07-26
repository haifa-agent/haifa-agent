package io.haifa.agent.testing.suite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuitePlanningIntegrationIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void plansPrivateSuiteAgainstPublicCatalogWithoutExecutingExternalTests() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path configRoot = Files.createDirectories(projectRoot.resolve("test-config"));
        Path runRoot = temporaryDirectory.resolve("runs");
        Files.createDirectories(configRoot.resolve("suites"));
        Files.createDirectories(configRoot.resolve("matrices"));
        Files.writeString(configRoot.resolve("matrices/primary-v1.yaml"), "schemaVersion: 1\n");
        Files.writeString(
                configRoot.resolve("suites/pr-real-v1.yaml"),
                """
                schemaVersion: 1
                suiteId: pr-real-v1
                matrixRef: primary-v1
                budget:
                  maxWallTimeMinutes: 30
                  maxEstimatedCostUsd: 3
                  maxParallelExternalCalls: 1
                cases:
                  - caseId: CP-01
                    repetitions: 1
                    blocking: true
                  - caseId: CP-09
                    repetitions: 1
                    blocking: true
                """);

        int exitCode = new SuiteRunnerMain()
                .run(new SuiteRunnerMain.Options(projectRoot, configRoot, runRoot, "pr-real-v1", false), Map.of());

        assertThat(exitCode).isZero();
        assertThat(runRoot).doesNotExist();
    }
}
