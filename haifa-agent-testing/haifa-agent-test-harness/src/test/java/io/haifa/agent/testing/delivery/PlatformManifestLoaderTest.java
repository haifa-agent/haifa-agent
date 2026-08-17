package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.haifa.agent.testing.harness.PlatformManifest;
import io.haifa.agent.testing.harness.PlatformManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformManifestLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void bindsWindowsCombinationToItsExactHostProfile() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("matrices"));
        Files.writeString(
                temporaryDirectory.resolve("matrices/autonomous-delivery-v1.yaml"),
                matrix(
                        "windows",
                        "conpty",
                        "host-guarded",
                        "allow",
                        "powershell",
                        "TRUSTED_HOST_ONLY",
                        "windows-host-trusted-v1"));

        PlatformManifest manifest = new PlatformManifestLoader().load(temporaryDirectory, "autonomous-delivery-v1");
        PlatformManifest.PlatformProfile combination = manifest.requireCombination("windows-host-trusted");

        assertEquals(
                "windows-host-trusted-v1",
                DeliveryPlatformProfiles.requireHost(combination, "Windows 11").id());
        assertThrows(IllegalArgumentException.class, () -> DeliveryPlatformProfiles.requireHost(combination, "Linux"));
    }

    @Test
    void bindsPosixTrustedHostDefaultWithoutClaimingLocalNativeIsolation() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("matrices"));
        Files.writeString(
                temporaryDirectory.resolve("matrices/autonomous-delivery-v1.yaml"),
                matrix(
                        "linux",
                        "unix-pty",
                        "host-guarded",
                        "allow",
                        "auto",
                        "TRUSTED_HOST_ONLY",
                        "trusted-host-default-v1"));

        PlatformManifest manifest = new PlatformManifestLoader().load(temporaryDirectory, "autonomous-delivery-v1");
        PlatformManifest.PlatformProfile combination = manifest.requireCombination("linux-host-default");

        assertEquals(
                "trusted-host-default-v1",
                DeliveryPlatformProfiles.requireHost(combination, "Linux").id());
        assertThrows(
                IllegalArgumentException.class, () -> DeliveryPlatformProfiles.requireHost(combination, "Windows 11"));
    }

    @Test
    void rejectsAWindowsCombinationThatClaimsLocalNativeIsolation() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("matrices"));
        Files.writeString(
                temporaryDirectory.resolve("matrices/autonomous-delivery-v1.yaml"),
                matrix(
                        "windows",
                        "conpty",
                        "local-native",
                        "deny",
                        "powershell",
                        "LOCAL_NATIVE",
                        "windows-host-trusted-v1"));

        PlatformManifest manifest = new PlatformManifestLoader().load(temporaryDirectory, "autonomous-delivery-v1");
        assertThrows(
                IllegalArgumentException.class,
                () -> DeliveryPlatformProfiles.requireHost(
                        manifest.requireCombination("windows-host-trusted"), "Windows 11"));
    }

    private static String matrix(
            String platform,
            String terminalBackend,
            String sandboxProfile,
            String networkPolicy,
            String shell,
            String isolationAssurance,
            String hostProfile) {
        String combinationId = platform.equals("windows") ? "windows-host-trusted" : platform + "-host-default";
        return """
                schemaVersion: 2
                matrixId: autonomous-delivery-v1
                strategy: explicit
                combinations:
                  - id: %s
                    platform: %s
                    terminalBackend: %s
                    sandboxProfile: %s
                    networkPolicy: %s
                    shell: %s
                    isolationAssurance: %s
                    hostProfile: %s
                    maxParallelExternalCalls: 1
                """
                .formatted(
                        combinationId,
                        platform,
                        terminalBackend,
                        sandboxProfile,
                        networkPolicy,
                        shell,
                        isolationAssurance,
                        hostProfile);
    }
}
