package io.haifa.agent.starter;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.sandbox.docker.DockerAioSandboxConfiguration;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerExecutionPlatformsTest {
    private static final String IMAGE = "ghcr.io/agent-infra/sandbox@sha256:" + "a".repeat(64);

    @TempDir
    Path workspaceRoot;

    @Test
    void exposesOneShotShellContributionWithoutDockerIo() {
        var configuration = new DockerAioSandboxConfiguration(
                "install-1",
                workspaceRoot.resolve("docker.sock"),
                IMAGE,
                "/mnt/user-data/workspace",
                Map.of("bash", "/bin/bash"),
                OptionalInt.empty(),
                OptionalInt.empty(),
                1_000_000_000L,
                256L * 1024 * 1024,
                128,
                Duration.ofSeconds(5),
                Duration.ofSeconds(2),
                8080);
        try (var platform = ContainerExecutionPlatforms.dockerAio(
                new ContainerExecutionPlatforms.ContainerExecutionSpec(
                        workspaceRoot, Duration.ofSeconds(30), Duration.ofMinutes(5), 1_048_576, 2_000),
                configuration,
                new TenantRef("tenant-1"),
                new PrincipalRef("principal-1", "USER"))) {
            assertThat(platform.toolNames()).containsExactly("execution_run");
            assertThat(platform.registration().definition().name().value()).isEqualTo("execution_run");
            assertThat(platform.registration().provider()).isNotNull();
            assertThat(platform.securitySummary()).contains("docker-aio").contains("Linux verification NOT_RUN");
        }
    }
}
