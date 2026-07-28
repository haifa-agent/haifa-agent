package io.haifa.agent.testing.suite;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuiteRunnerMainTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsIncompatibleEnvironmentDuringPlanOnlyPreflight() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path configRoot = Files.createDirectory(temporaryDirectory.resolve("test-config"));
        Path runRoot = temporaryDirectory.resolve("runs");
        Files.createDirectories(configRoot.resolve("suites"));
        Files.createDirectories(configRoot.resolve("matrices"));
        Files.createDirectories(configRoot.resolve("environments/cli"));
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
                """);
        Files.writeString(
                configRoot.resolve("environments/cli/interaction-live.yaml"),
                """
                persistence:
                  maximumPayloadBytes: 4194304
                """);

        assertThrows(IllegalArgumentException.class, () -> new SuiteRunnerMain()
                .run(new SuiteRunnerMain.Options(projectRoot, configRoot, runRoot, "pr-real-v1", false), Map.of()));
    }
}
