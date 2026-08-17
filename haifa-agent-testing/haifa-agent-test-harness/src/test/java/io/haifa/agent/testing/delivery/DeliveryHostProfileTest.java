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
    void exactExecutablesBuildAHostNativeMinimalPath() throws Exception {
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
    }
}
