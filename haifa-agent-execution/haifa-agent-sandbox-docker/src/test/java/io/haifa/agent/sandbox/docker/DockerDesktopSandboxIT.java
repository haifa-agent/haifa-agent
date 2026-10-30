package io.haifa.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.sandbox.api.SandboxExecution;
import io.haifa.agent.sandbox.api.SandboxProcessStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Opt-in real-daemon test: named-pipe transport, NTFS sharing and UID/GID behavior require Docker's
 * process boundary and cannot be established by the in-memory engine. The image must be preloaded.
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "haifa.docker.desktop.it", matches = "true")
class DockerDesktopSandboxIT extends AbstractDockerAioProviderTest {
    @Override
    protected DockerAioLifecycleMode lifecycleMode() {
        return DockerAioLifecycleMode.EPHEMERAL;
    }

    @Test
    void bothLifecyclesReadAndWriteTheHostWorkspaceAsTheConfiguredUser() throws Exception {
        provider.close();
        String image = System.getProperty("haifa.docker.desktop.image");
        assertThat(image).as("Supply a preloaded digest-locked AIO image").isNotBlank();
        var sequence = new AtomicInteger();
        for (var mode : DockerAioLifecycleMode.values()) {
            var config = new DockerAioSandboxConfiguration(
                    "desktop-it-" + UUID.randomUUID(),
                    DockerAioSandboxConfiguration.localDockerSocket(),
                    image,
                    "/mnt/user-data/workspace",
                    Map.of("bash", "/bin/bash"),
                    OptionalInt.of(1000),
                    OptionalInt.of(1000),
                    2_000_000_000L,
                    2L * 1024 * 1024 * 1024,
                    512,
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(10),
                    8080,
                    1,
                    mode,
                    Duration.ofMinutes(1));
            Files.writeString(workspaceRoot.resolve("host.txt"), "from-host", StandardCharsets.UTF_8);
            try (var desktop = new DockerAioSandboxProvider(
                            config,
                            workspaces,
                            locations,
                            () -> "session-" + sequence.incrementAndGet(),
                            Instant::now);
                    var session = desktop.open(createProfile(config), workspaceId)) {
                var first = session.execute(realExecution("test \"$(id -u)\" = 1000 && test \"$(id -g)\" = 1000"
                        + " && test \"$(cat host.txt)\" = from-host && printf from-container > container.txt"));
                assertThat(first.status()).isEqualTo(SandboxProcessStatus.EXITED);
                assertThat(first.exitCode()).isZero();
                assertThat(Files.readString(workspaceRoot.resolve("container.txt"), StandardCharsets.UTF_8))
                        .isEqualTo("from-container");
                Files.writeString(workspaceRoot.resolve("container.txt"), "host-updated", StandardCharsets.UTF_8);
                var second = session.execute(realExecution("cat container.txt"));
                assertThat(second.status()).isEqualTo(SandboxProcessStatus.EXITED);
                assertThat(second.exitCode()).isZero();
                assertThat(new String(second.stdout(), StandardCharsets.UTF_8)).isEqualTo("host-updated");
            }
        }
    }

    private SandboxExecution realExecution(String command) {
        return new SandboxExecution(
                ExecutionCommand.shell(command),
                WorkspacePath.root(workspaceId),
                Map.of(),
                new ExecutionLimits(Duration.ofSeconds(90), 1024, 1024));
    }
}
