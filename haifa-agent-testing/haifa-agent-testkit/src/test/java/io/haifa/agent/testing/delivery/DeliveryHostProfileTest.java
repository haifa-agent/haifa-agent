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
    void trustedHostDefaultsAlignExecutionSemanticsWhileStrictProfilesRemainExplicit() {
        DeliveryHostProfile mac = DeliveryHostProfile.require("trusted-host-default-v1", "Mac OS X");
        DeliveryHostProfile linux = DeliveryHostProfile.require("trusted-host-default-v1", "Linux");
        DeliveryHostProfile windows = DeliveryHostProfile.require("trusted-host-default-v1", "Windows 11");
        DeliveryHostProfile strict = DeliveryHostProfile.require("posix-local-native-v1", "Linux");
        DeliveryHostProfile windowsConpty = DeliveryHostProfile.require("windows-host-trusted-v1", "Windows 11");

        assertEquals("macos", mac.platform());
        assertEquals("linux", linux.platform());
        assertEquals("host-guarded", mac.executionProvider());
        assertEquals("host-guarded", linux.executionProvider());
        assertEquals("allow", linux.networkPolicy());
        assertEquals("auto", linux.shell());
        assertEquals("TRUSTED_HOST_ONLY", linux.isolationAssurance());
        assertEquals("mvnw", linux.mavenWrapperName());
        assertEquals("windows", windows.platform());
        assertEquals("conpty", windows.terminalBackend());
        assertEquals("host-guarded", windows.executionProvider());
        assertEquals("allow", windows.networkPolicy());
        assertEquals("auto", windows.shell());
        assertEquals("TRUSTED_HOST_ONLY", windows.isolationAssurance());
        assertEquals("mvnw.cmd", windows.mavenWrapperName());
        assertEquals("local-native", strict.executionProvider());
        assertEquals("deny", strict.networkPolicy());
        assertEquals("LOCAL_NATIVE", strict.isolationAssurance());
        assertEquals("powershell", windowsConpty.shell());
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
        for (String name : List.of("python", "node", "go", "git", "shell")) {
            Path bin = Files.createDirectories(temporary.resolve(name).resolve("bin"));
            paths.put(name, Files.createFile(bin.resolve(name)));
        }
        DeliveryToolchainSet toolchains = DeliveryToolchainSet.validate(paths);

        assertEquals(6, toolchains.minimalPath().split(java.util.regex.Pattern.quote(File.pathSeparator)).length);
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
        String trustedPosix = DeliveryCliConfigurationFactory.render(
                suite,
                toolchains,
                DeliveryHostProfile.require("trusted-host-default-v1", "Linux"),
                combination("linux-deepseek-host-default", "linux", "trusted-host-default-v1"));
        String strictPosix = DeliveryCliConfigurationFactory.render(
                suite,
                toolchains,
                DeliveryHostProfile.require("posix-local-native-v1", "Linux"),
                combination("linux-deepseek-local-native", "linux", "posix-local-native-v1"));
        String trustedWindows = DeliveryCliConfigurationFactory.render(
                suite,
                toolchains,
                DeliveryHostProfile.require("trusted-host-default-v1", "Windows 11"),
                combination("windows-deepseek-host-default", "windows", "trusted-host-default-v1"));
        String windows = DeliveryCliConfigurationFactory.render(
                suite,
                toolchains,
                DeliveryHostProfile.require("windows-host-trusted-v1", "Windows 11"),
                combination("windows-deepseek-host-trusted", "windows", "windows-host-trusted-v1"));

        assertTrue(trustedPosix.contains("provider: host-guarded"));
        assertTrue(trustedPosix.contains("network: allow"));
        assertTrue(trustedPosix.contains("shell: auto"));
        assertTrue(strictPosix.contains("provider: local-native"));
        assertTrue(strictPosix.contains("network: deny"));
        assertTrue(trustedWindows.contains("provider: host-guarded"));
        assertTrue(trustedWindows.contains("network: allow"));
        assertTrue(trustedWindows.contains("shell: auto"));
        assertFalse(trustedWindows.contains("shellPath:"));
        assertTrue(windows.contains("provider: host-guarded"));
        assertTrue(windows.contains("network: allow"));
        assertTrue(windows.contains("shell: powershell"));
        assertTrue(windows.contains("shellPath: '" + toolchains.shellExecutable() + "'"));
        assertTrue(windows.contains("default: deepseek-v4-flash"));
        assertTrue(strictPosix.contains("java-toolchain"));
        assertTrue(trustedPosix.contains("extraPathPolicies: []"));
        assertTrue(windows.contains("extraPathPolicies: []"));
        assertFalse(trustedPosix.contains("java-toolchain"));
        assertFalse(windows.contains("java-toolchain"));
        assertFalse(strictPosix.contains("/usr/bin"));
        assertFalse(windows.contains("/usr/bin"));
    }

    private static AutonomousDeliveryMatrixManifest.Combination combination(
            String id, String platform, String hostProfile) {
        boolean windows = platform.equals("windows");
        boolean strict = hostProfile.equals("posix-local-native-v1");
        boolean explicitWindows = hostProfile.equals("windows-host-trusted-v1");
        return new AutonomousDeliveryMatrixManifest.Combination(
                id,
                platform,
                "deepseek",
                "deepseek-v4-flash",
                windows ? "conpty" : "unix-pty",
                strict ? "local-native" : "host-guarded",
                strict ? "deny" : "allow",
                explicitWindows ? "powershell" : "auto",
                strict ? "LOCAL_NATIVE" : "TRUSTED_HOST_ONLY",
                hostProfile,
                1);
    }
}
