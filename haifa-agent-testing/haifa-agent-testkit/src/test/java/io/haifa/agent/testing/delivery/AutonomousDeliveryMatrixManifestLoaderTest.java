package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutonomousDeliveryMatrixManifestLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void bindsWindowsCombinationToItsExactHostProfile() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("matrices"));
        Files.writeString(
                temporaryDirectory.resolve("matrices/autonomous-delivery-v1.yaml"),
                matrix("conpty", "host-guarded", "allow", "powershell", "TRUSTED_HOST_ONLY"));

        AutonomousDeliveryMatrixManifest manifest =
                new AutonomousDeliveryMatrixManifestLoader().load(temporaryDirectory, "autonomous-delivery-v1");
        AutonomousDeliveryMatrixManifest.Combination combination =
                manifest.requireCombination("windows-deepseek-host-trusted");

        assertEquals(
                "windows-host-trusted-v1", combination.requireHost("Windows 11").id());
        assertThrows(IllegalArgumentException.class, () -> combination.requireHost("Linux"));
    }

    @Test
    void rejectsAWindowsCombinationThatClaimsLocalNativeIsolation() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("matrices"));
        Files.writeString(
                temporaryDirectory.resolve("matrices/autonomous-delivery-v1.yaml"),
                matrix("conpty", "local-native", "deny", "powershell", "LOCAL_NATIVE"));

        assertThrows(IllegalArgumentException.class, () -> new AutonomousDeliveryMatrixManifestLoader()
                .load(temporaryDirectory, "autonomous-delivery-v1"));
    }

    private static String matrix(
            String terminalBackend,
            String sandboxProfile,
            String networkPolicy,
            String shell,
            String isolationAssurance) {
        return """
                schemaVersion: 1
                matrixId: autonomous-delivery-v1
                compatibleAgentBaselineCommit: cc9ddb902b0db0e8e85b81bb7418eff9fd66f6ed
                strategy: explicit
                combinations:
                  - id: windows-deepseek-host-trusted
                    platform: windows
                    modelProvider: deepseek
                    modelId: deepseek-chat
                    terminalBackend: %s
                    sandboxProfile: %s
                    networkPolicy: %s
                    shell: %s
                    isolationAssurance: %s
                    hostProfile: windows-host-trusted-v1
                    maxParallelExternalCalls: 1
                """
                .formatted(terminalBackend, sandboxProfile, networkPolicy, shell, isolationAssurance);
    }
}
