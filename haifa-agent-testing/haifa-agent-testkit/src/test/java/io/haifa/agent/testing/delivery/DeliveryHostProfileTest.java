package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeliveryHostProfileTest {
    @TempDir
    Path temporary;

    @Test
    void profilesKeepOnlyRealPlatformDifferences() {
        DeliveryHostProfile mac = DeliveryHostProfile.require("posix-local-native-v1", "Mac OS X");
        DeliveryHostProfile linux = DeliveryHostProfile.require("posix-local-native-v1", "Linux");
        DeliveryHostProfile windows = DeliveryHostProfile.require("windows-host-trusted-v1", "Windows 11");

        assertEquals("macos", mac.platform());
        assertEquals("linux", linux.platform());
        assertEquals("local-native", linux.executionProvider());
        assertEquals("deny", linux.networkPolicy());
        assertEquals("mvnw", linux.mavenWrapperName());
        assertEquals("windows", windows.platform());
        assertEquals("conpty", windows.terminalBackend());
        assertEquals("host-guarded", windows.executionProvider());
        assertEquals("TRUSTED_HOST_ONLY", windows.isolationAssurance());
        assertEquals("mvnw.cmd", windows.mavenWrapperName());
        assertTrue(linux.terminalDriverSupported());
        assertTrue(windows.terminalDriverSupported());
        assertThrows(
                IllegalArgumentException.class, () -> DeliveryHostProfile.require("windows-host-trusted-v1", "Linux"));
    }

    @Test
    void exactExecutablesBuildAHostNativeMinimalPathAndSharedConfiguration() throws Exception {
        Path jdkBin = Files.createDirectories(temporary.resolve("jdk/bin"));
        LinkedHashMap<String, Path> paths = new LinkedHashMap<>();
        paths.put("java", Files.createFile(jdkBin.resolve("java")));
        paths.put("javac", Files.createFile(jdkBin.resolve("javac")));
        for (String name : List.of("python", "node", "go", "git")) {
            Path bin = Files.createDirectories(temporary.resolve(name).resolve("bin"));
            paths.put(name, Files.createFile(bin.resolve(name)));
        }
        DeliveryToolchainSet toolchains = DeliveryToolchainSet.validate(paths);

        assertEquals(5, toolchains.minimalPath().split(java.util.regex.Pattern.quote(File.pathSeparator)).length);
        assertFalse(toolchains.minimalPath().contains("/usr/bin"));
        AutonomousDeliverySuiteManifest suite = new AutonomousDeliverySuiteManifest(
                1,
                "test-v1",
                AutonomousDeliveryCaseCatalog.EXPECTED_CATALOG_ID,
                "PHASE_1",
                "matrix-v1",
                null,
                null,
                new AutonomousDeliverySuiteManifest.Budget(1000, 2, 3, 4, 1),
                List.of(new AutonomousDeliverySuiteManifest.CaseSelection("01", 1, true)));
        String posix = DeliveryCliConfigurationFactory.render(
                suite,
                toolchains,
                DeliveryHostProfile.require("posix-local-native-v1", "Linux"),
                combination("linux-deepseek-local-native", "linux"));
        String windows = DeliveryCliConfigurationFactory.render(
                suite,
                toolchains,
                DeliveryHostProfile.require("windows-host-trusted-v1", "Windows 11"),
                combination("windows-deepseek-host-trusted", "windows"));

        assertTrue(posix.contains("provider: local-native"));
        assertTrue(posix.contains("network: deny"));
        assertTrue(windows.contains("provider: host-guarded"));
        assertTrue(windows.contains("network: allow"));
        assertTrue(windows.contains("shell: powershell"));
        assertTrue(windows.contains("default: deepseek-v4-flash"));
        assertFalse(posix.contains("/usr/bin"));
        assertFalse(windows.contains("/usr/bin"));
    }

    private static AutonomousDeliveryMatrixManifest.Combination combination(String id, String platform) {
        boolean windows = platform.equals("windows");
        return new AutonomousDeliveryMatrixManifest.Combination(
                id,
                platform,
                "deepseek",
                "deepseek-v4-flash",
                windows ? "conpty" : "unix-pty",
                windows ? "host-guarded" : "local-native",
                windows ? "allow" : "deny",
                windows ? "powershell" : "auto",
                windows ? "TRUSTED_HOST_ONLY" : "LOCAL_NATIVE",
                windows ? "windows-host-trusted-v1" : "posix-local-native-v1",
                1);
    }
}
