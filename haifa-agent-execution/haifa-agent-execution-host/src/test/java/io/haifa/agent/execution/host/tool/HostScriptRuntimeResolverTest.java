package io.haifa.agent.execution.host.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.execution.core.tool.ExecutionOperatingSystem;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class HostScriptRuntimeResolverTest {
    @Test
    void windowsUsesConfiguredPowerShellAndOptionalPython() {
        withOsName("Windows 11", () -> {
            var resolver = HostScriptRuntimeResolver.currentHost(
                    Optional.of(Path.of("C:/runtime/python.exe")), Optional.of(Path.of("C:/runtime/pwsh.exe")));

            assertThat(resolver.operatingSystem()).isEqualTo(ExecutionOperatingSystem.WINDOWS);
            assertThat(resolver.resolve("powershell")
                            .prepare("Write-Output ok", List.of())
                            .command()
                            .argv())
                    .first()
                    .isEqualTo(Path.of("C:/runtime/pwsh.exe").toString());
            assertThat(resolver.resolve("python")
                            .prepare("print('ok')", List.of())
                            .command()
                            .argv())
                    .first()
                    .isEqualTo(Path.of("C:/runtime/python.exe").toString());
            assertThatThrownBy(() -> resolver.resolve("bash")).isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void posixDefaultsToBashWithoutInventingOptionalRuntimes() {
        withOsName("Linux", () -> {
            var resolver = HostScriptRuntimeResolver.currentHost(Optional.empty(), Optional.empty());

            assertThat(resolver.operatingSystem()).isEqualTo(ExecutionOperatingSystem.LINUX);
            assertThat(resolver.resolve("bash")
                            .prepare("printf ok", List.of())
                            .command()
                            .argv())
                    .startsWith("/bin/bash", "--noprofile", "--norc", "-s", "--");
            assertThatThrownBy(() -> resolver.resolve("python")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> resolver.resolve("powershell")).isInstanceOf(IllegalArgumentException.class);
        });
    }

    private static void withOsName(String osName, Runnable assertion) {
        String previous = System.getProperty("os.name");
        try {
            System.setProperty("os.name", osName);
            assertion.run();
        } finally {
            if (previous == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", previous);
            }
        }
    }
}
