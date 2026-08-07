package io.haifa.agent.personalassistant.server.configuration.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalExecutionRuntimeTest {
    @TempDir
    Path root;

    @Test
    void windowsHostUserEnvironmentKeepsPythonUserSiteOutsideExecutionWorkspace() throws Exception {
        Path userProfile = Files.createDirectory(root.resolve("user-profile"));
        Path appData = Files.createDirectories(userProfile.resolve("AppData/Roaming"));
        Path localAppData = Files.createDirectories(userProfile.resolve("AppData/Local"));
        Path productData = Files.createDirectory(root.resolve("personal-data"));
        Path workspace = Files.createDirectory(productData.resolve("execution-workspace"));
        Path scratch = Files.createDirectory(root.resolve("scratch"));

        var resolved = PersonalExecutionRuntime.resolveHostEnvironment(
                Map.of(
                        "USERPROFILE", userProfile.toString(),
                        "APPDATA", appData.toString(),
                        "LOCALAPPDATA", localAppData.toString(),
                        "PATH", "tools",
                        "SystemRoot", root.resolve("Windows").toString(),
                        "PATHEXT", ".EXE;.CMD"),
                "Windows 11",
                userProfile,
                productData,
                workspace,
                scratch);

        Path pythonUserSite = Path.of(resolved.environment().get("APPDATA"))
                .resolve("Python/Python311/site-packages")
                .normalize();
        assertThat(Path.of(resolved.environment().get("HOME"))).isEqualTo(userProfile.toRealPath());
        assertThat(resolved.environment())
                .containsEntry("USERPROFILE", userProfile.toString())
                .containsEntry("APPDATA", appData.toString())
                .containsEntry("LOCALAPPDATA", localAppData.toString());
        assertThat(pythonUserSite.startsWith(workspace)).isFalse();
        assertThat(workspace.resolve("~")).doesNotExist();
    }
}
