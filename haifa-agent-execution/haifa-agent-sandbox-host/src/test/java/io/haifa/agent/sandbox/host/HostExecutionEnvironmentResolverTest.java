package io.haifa.agent.sandbox.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostExecutionEnvironmentResolverTest {
    @TempDir
    Path root;

    private Path home;
    private Path applicationData;
    private Path workspace;
    private Path scratch;

    @BeforeEach
    void prepare() throws Exception {
        home = Files.createDirectory(root.resolve("home"));
        applicationData = Files.createDirectory(root.resolve("application-data"));
        workspace = Files.createDirectory(root.resolve("workspace"));
        scratch = Files.createDirectory(root.resolve("scratch"));
    }

    @Test
    void windowsUsesDeterministicHomePrecedenceAndCaseInsensitiveBaseline() throws Exception {
        Path profile = Files.createDirectory(root.resolve("profile"));
        Path appData = Files.createDirectory(root.resolve("roaming"));
        Path localAppData = Files.createDirectory(root.resolve("local"));
        var result = HostExecutionEnvironmentResolver.resolveHostUser(
                Map.of(
                        "home", workspace.toString(),
                        "UserProfile", profile.toString(),
                        "APPDATA", appData.toString(),
                        "LOCALAPPDATA", localAppData.toString(),
                        "Path", "tools",
                        "PATHEXT", ".EXE;.CMD",
                        "DEEPSEEK_API_KEY", "secret",
                        "PYTHONUSERBASE", root.resolve("python").toString()),
                "Windows 11",
                home,
                applicationData,
                workspace,
                scratch,
                Set.of("*"));

        assertThat(result.environment())
                .containsEntry("HOME", profile.toRealPath().toString())
                .containsEntry("UserProfile", profile.toRealPath().toString())
                .containsEntry("APPDATA", appData.toRealPath().toString())
                .containsEntry("LOCALAPPDATA", localAppData.toRealPath().toString())
                .doesNotContainKeys("DEEPSEEK_API_KEY", "PYTHONUSERBASE");
        assertThat(result.allowedEnvironmentNames())
                .isEqualTo(result.environment().keySet());
        assertThat(result.diagnosticCode()).isEqualTo(HostExecutionEnvironmentResolver.HOST_USER_RESOLVED);
    }

    @Test
    void windowsPrefersSafeHomeOverUserProfile() throws Exception {
        Path profile = Files.createDirectory(root.resolve("lower-priority-profile"));
        var result = HostExecutionEnvironmentResolver.resolveHostUser(
                Map.of("HOME", home.toString(), "USERPROFILE", profile.toString()),
                "Windows 11",
                profile,
                applicationData,
                workspace,
                scratch,
                Set.of());

        assertThat(result.environment()).containsEntry("HOME", home.toRealPath().toString());
    }

    @Test
    void windowsUsesHomeDriveAndHomePathBeforeJvmFallback() throws Exception {
        Path driveHome = Files.createDirectory(root.resolve("drive-home"));
        Path fileSystemRoot = driveHome.getRoot();
        var result = HostExecutionEnvironmentResolver.resolveHostUser(
                Map.of(
                        "HOME", workspace.toString(),
                        "HOMEDRIVE", fileSystemRoot.toString(),
                        "HOMEPATH", fileSystemRoot.relativize(driveHome).toString()),
                "Windows 11",
                home,
                applicationData,
                workspace,
                scratch,
                Set.of());

        assertThat(result.environment())
                .containsEntry("HOME", driveHome.toRealPath().toString());
    }

    @Test
    void windowsPreservesTheCompleteHostBaseline() throws Exception {
        Path profile = Files.createDirectory(root.resolve("baseline-profile"));
        Path appData = Files.createDirectory(root.resolve("baseline-roaming"));
        Path localAppData = Files.createDirectory(root.resolve("baseline-local"));
        Path systemRoot = Files.createDirectory(root.resolve("Windows"));
        Path temporary = Files.createDirectory(root.resolve("host-temp"));
        var result = HostExecutionEnvironmentResolver.resolveHostUser(
                Map.ofEntries(
                        Map.entry("PATH", "tools"),
                        Map.entry("PATHEXT", ".EXE;.CMD"),
                        Map.entry("SystemRoot", systemRoot.toString()),
                        Map.entry("SystemDrive", profile.getRoot().toString()),
                        Map.entry("WINDIR", systemRoot.toString()),
                        Map.entry("ComSpec", root.resolve("powershell.exe").toString()),
                        Map.entry("TEMP", temporary.toString()),
                        Map.entry("TMP", temporary.toString()),
                        Map.entry("USERPROFILE", profile.toString()),
                        Map.entry("APPDATA", appData.toString()),
                        Map.entry("LOCALAPPDATA", localAppData.toString()),
                        Map.entry("HOMEDRIVE", profile.getRoot().toString()),
                        Map.entry(
                                "HOMEPATH",
                                profile.getRoot().relativize(profile).toString())),
                "Windows 11",
                home,
                applicationData,
                workspace,
                scratch,
                Set.of());

        assertThat(result.environment())
                .containsKeys(
                        "PATH",
                        "PATHEXT",
                        "SystemRoot",
                        "SystemDrive",
                        "WINDIR",
                        "ComSpec",
                        "TEMP",
                        "TMP",
                        "USERPROFILE",
                        "APPDATA",
                        "LOCALAPPDATA",
                        "HOMEDRIVE",
                        "HOMEPATH");
    }

    @Test
    void linuxKeepsSafeXdgDirectoriesButRejectsWorkspaceAndInterpreterOverrides() throws Exception {
        Path xdgData = Files.createDirectory(root.resolve("xdg-data"));
        var result = HostExecutionEnvironmentResolver.resolveHostUser(
                Map.of(
                        "HOME", home.toString(),
                        "XDG_DATA_HOME", xdgData.toString(),
                        "XDG_CACHE_HOME", workspace.toString(),
                        "PYTHONPATH", root.resolve("python").toString(),
                        "NODE_PATH", root.resolve("node").toString()),
                "Linux",
                home,
                applicationData,
                workspace,
                scratch,
                Set.of("*"));

        assertThat(result.environment())
                .containsEntry("HOME", home.toRealPath().toString())
                .containsEntry("XDG_DATA_HOME", xdgData.toRealPath().toString())
                .containsEntry("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                .doesNotContainKeys("XDG_CACHE_HOME", "PYTHONPATH", "NODE_PATH");
    }

    @Test
    void macUsesPosixHomeAndMacCommandFallbacks() throws Exception {
        var result = HostExecutionEnvironmentResolver.resolveHostUser(
                Map.of("HOME", home.toString()), "Mac OS X", home, applicationData, workspace, scratch, Set.of());

        assertThat(result.environment())
                .containsEntry("HOME", home.toRealPath().toString())
                .containsEntry("SHELL", "/bin/zsh")
                .containsEntry("PATH", "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin");
    }

    @Test
    void usesJvmHomeWhenEnvironmentHasNoSafeHome() throws Exception {
        var result = HostExecutionEnvironmentResolver.resolveHostUser(
                Map.of("HOME", workspace.toString()), "Linux", home, applicationData, workspace, scratch, Set.of());

        assertThat(result.environment()).containsEntry("HOME", home.toRealPath().toString());
    }

    @Test
    void removesKnownDirectoryVariablesThatAreRelativeOrInsideTheWorkspace() {
        var result = HostExecutionEnvironmentResolver.resolveHostUser(
                Map.of(
                        "HOME", home.toString(),
                        "APPDATA", "relative-app-data",
                        "LOCALAPPDATA", workspace.toString(),
                        "TEMP", scratch.toString()),
                "Windows 11",
                home,
                applicationData,
                workspace,
                scratch,
                Set.of("*"));

        assertThat(result.environment()).doesNotContainKeys("APPDATA", "LOCALAPPDATA", "TEMP");
    }

    @Test
    void providerIsolatedNeverPassesHostHomeOrUserDirectoryVariables() {
        var result = HostExecutionEnvironmentResolver.resolveProviderIsolated(
                Map.of(
                        "PATH", "/custom/bin",
                        "HOME", home.toString(),
                        "USERPROFILE", home.toString(),
                        "APPDATA", root.resolve("appdata").toString(),
                        "XDG_DATA_HOME", root.resolve("xdg").toString(),
                        "TMPDIR", root.resolve("tmp").toString()),
                "Linux",
                Set.of("*"));

        assertThat(result.environment())
                .containsEntry("PATH", "/custom/bin")
                .doesNotContainKeys("HOME", "USERPROFILE", "APPDATA", "XDG_DATA_HOME", "TMPDIR");
        assertThat(result.diagnosticCode()).isEqualTo(HostExecutionEnvironmentResolver.PROVIDER_ISOLATED_RESOLVED);
    }

    @Test
    void hostUserFailsClosedWhenEveryHomeCandidateIsUnsafeOrUnavailable() {
        assertThatThrownBy(() -> HostExecutionEnvironmentResolver.resolveHostUser(
                        Map.of("HOME", workspace.toString(), "USERPROFILE", applicationData.toString()),
                        "Windows 11",
                        scratch,
                        applicationData,
                        workspace,
                        scratch,
                        Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HOST_USER_HOME_UNAVAILABLE");
    }
}
