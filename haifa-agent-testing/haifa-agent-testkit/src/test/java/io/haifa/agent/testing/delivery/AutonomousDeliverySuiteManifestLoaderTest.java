package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutonomousDeliverySuiteManifestLoaderTest {
    @TempDir
    Path temporary;

    @Test
    void loadsStableCaseReferences() throws Exception {
        writeSuite("01");

        AutonomousDeliverySuiteManifest manifest = new AutonomousDeliverySuiteManifestLoader()
                .load(temporary, "phase-test-v1", AutonomousDeliveryCaseCatalog.loadVerified());

        assertEquals("PHASE_1", manifest.phase());
        assertEquals("01", manifest.cases().getFirst().caseId());
    }

    @Test
    void rejectsUnknownPublicCaseBeforeExecution() throws Exception {
        writeSuite("11");

        assertThrows(IllegalArgumentException.class, () -> new AutonomousDeliverySuiteManifestLoader()
                .load(temporary, "phase-test-v1", AutonomousDeliveryCaseCatalog.loadVerified()));
    }

    private void writeSuite(String caseId) throws Exception {
        Path suites = Files.createDirectory(temporary.resolve("suites"));
        Files.writeString(
                suites.resolve("phase-test-v1.yaml"),
                """
                schemaVersion: 1
                suiteId: phase-test-v1
                catalogRef: generalized-coding-v1
                phase: PHASE_1
                matrixRef: matrix-v1
                budget:
                  maxWallTimeMillis: 1800000
                  maxIterations: 80
                  maxToolCalls: 96
                  maxModelCalls: 64
                  maxParallelExternalCalls: 1
                cases:
                  - caseId: "%s"
                    repetitions: 1
                    blocking: true
                """
                        .formatted(caseId));
    }
}
