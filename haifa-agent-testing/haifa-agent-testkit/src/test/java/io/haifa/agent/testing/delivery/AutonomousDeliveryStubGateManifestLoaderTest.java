package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutonomousDeliveryStubGateManifestLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsReviewedZeroCostStubGateWithoutCodingCases() throws Exception {
        Path suites = Files.createDirectory(temporaryDirectory.resolve("suites"));
        Files.writeString(suites.resolve("windows-stub-v1.yaml"), manifest("STUB", 0, 0.0));

        AutonomousDeliveryStubGateManifest manifest =
                new AutonomousDeliveryStubGateManifestLoader().load(temporaryDirectory, "windows-stub-v1");

        assertEquals("PLATFORM_STUB", manifest.gateType());
        assertEquals("STUB", manifest.dependencyMode());
        assertEquals(AutonomousDeliveryStubGateManifest.REQUIRED_CHECKS, Set.copyOf(manifest.requiredChecks()));
    }

    @Test
    void rejectsLiveDependencyOrExternalBudget() throws Exception {
        Path suites = Files.createDirectory(temporaryDirectory.resolve("suites"));
        Files.writeString(suites.resolve("live-stub-v1.yaml"), manifest("LIVE", 1, 1.0));

        assertThrows(ValueInstantiationException.class, () -> new AutonomousDeliveryStubGateManifestLoader()
                .load(temporaryDirectory, "live-stub-v1"));
    }

    private static String manifest(String dependencyMode, int parallel, double cost) {
        return """
                schemaVersion: 1
                suiteId: %s
                gateType: PLATFORM_STUB
                dependencyMode: %s
                platform: windows
                matrixRef: autonomous-delivery-v1
                budget:
                  maxWallTimeMillis: 600000
                  maxParallelExternalCalls: %d
                  maxEstimatedCostUsd: %s
                requiredChecks:
                  - CONPTY
                  - APPROVAL
                  - SHELL
                  - SQLITE
                  - SECRET_SCAN
                  - EVIDENCE
                  - PROCESS_TREE
                  - WORKSPACE_CLEANUP
                  - REPOSITORY_STABILITY
                  - NO_EXTERNAL_PROVIDER
                """
                .formatted(
                        dependencyMode.equals("STUB") ? "windows-stub-v1" : "live-stub-v1",
                        dependencyMode,
                        parallel,
                        cost);
    }
}
