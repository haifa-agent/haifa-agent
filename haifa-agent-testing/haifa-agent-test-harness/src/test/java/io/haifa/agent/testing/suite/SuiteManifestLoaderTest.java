package io.haifa.agent.testing.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuiteManifestLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsKnownCasesAndRequiresReferencedMatrix() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("suites"));
        Files.createDirectories(temporaryDirectory.resolve("matrices"));
        Files.writeString(temporaryDirectory.resolve("matrices/primary-v1.yaml"), "schemaVersion: 1\n");
        Files.writeString(
                temporaryDirectory.resolve("suites/critical-path-smoke-v1.yaml"),
                """
                schemaVersion: 1
                suiteId: critical-path-smoke-v1
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

        SuiteManifest value = new SuiteManifestLoader().load(temporaryDirectory, "critical-path-smoke-v1");

        assertEquals("critical-path-smoke-v1", value.suiteId());
        assertEquals("CP-01", value.cases().getFirst().caseId());
    }

    @Test
    void rejectsUnknownCaseInsteadOfSilentlySkippingIt() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("suites"));
        Files.createDirectories(temporaryDirectory.resolve("matrices"));
        Files.writeString(temporaryDirectory.resolve("matrices/primary-v1.yaml"), "schemaVersion: 1\n");
        Files.writeString(
                temporaryDirectory.resolve("suites/critical-path-smoke-v1.yaml"),
                """
                schemaVersion: 1
                suiteId: critical-path-smoke-v1
                matrixRef: primary-v1
                budget:
                  maxWallTimeMinutes: 30
                  maxEstimatedCostUsd: 3
                  maxParallelExternalCalls: 1
                cases:
                  - caseId: CP-99
                    repetitions: 1
                    blocking: true
                """);

        assertThrows(IllegalArgumentException.class, () -> new SuiteManifestLoader()
                .load(temporaryDirectory, "critical-path-smoke-v1"));
    }
}
