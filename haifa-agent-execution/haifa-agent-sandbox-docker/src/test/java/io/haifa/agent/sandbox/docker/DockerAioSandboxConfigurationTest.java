package io.haifa.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class DockerAioSandboxConfigurationTest {
    private static final String IMAGE = "ghcr.io/example/sandbox@sha256:" + "a".repeat(64);

    @Test
    void localEndpointResolutionRejectsRemoteOrAmbiguousHosts() {
        assertThat(DockerAioSandboxConfiguration.localDockerSocket("Linux", null))
                .isEqualTo(Path.of("/var/run/docker.sock"));
        assertThat(DockerAioSandboxConfiguration.localDockerSocket("Linux", "unix:///tmp/custom.sock"))
                .isEqualTo(Path.of("/tmp/custom.sock"));
        for (String endpoint : java.util.List.of("tcp://localhost:2375", "ssh://example", "unix://host/socket",
                "npipe:////./pipe/docker_engine?x=1", "npipe://remote//./pipe/docker_engine")) {
            assertThatThrownBy(() -> DockerAioSandboxConfiguration.localDockerSocket("Windows 11", endpoint))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void nativeNamedPipeSurvivesPathNormalizationAndBuildsTheTransport() throws Exception {
        Path socket = DockerAioSandboxConfiguration.localDockerSocket("Windows 11", null);
        assertThat(socket).isEqualTo(Path.of("\\\\.\\pipe\\docker_engine"));
        assertThat(DockerAioSandboxConfiguration.localDockerSocket("Windows 11", "npipe:////./pipe/docker_engine"))
                .isEqualTo(socket);
        var config = configuration(IMAGE, OptionalInt.empty(), socket);
        assertThat(config.dockerEndpoint().toString()).isEqualTo("npipe:////./pipe/docker_engine");
        try (var engine = new DockerJavaEngine(config)) {
            // Transport construction is local; no daemon or image is required.
            assertThat(engine).isNotNull();
        }
    }

    @Test
    void digestChangesWithEveryTrustedIdentityField() {
        DockerAioSandboxConfiguration first = configuration(IMAGE, OptionalInt.empty());
        DockerAioSandboxConfiguration same = configuration(IMAGE, OptionalInt.empty());
        DockerAioSandboxConfiguration otherImage =
                configuration("ghcr.io/example/other@sha256:" + "b".repeat(64), OptionalInt.empty());
        DockerAioSandboxConfiguration otherUser = configuration(IMAGE, OptionalInt.of(1000));

        assertThat(first.configurationDigest()).isEqualTo(same.configurationDigest());
        assertThat(first.configurationDigest()).isNotEqualTo(otherImage.configurationDigest());
        assertThat(first.configurationDigest()).isNotEqualTo(otherUser.configurationDigest());
    }

    @Test
    void digestReflectsVersionTwoPolicyBoundary() {
        DockerAioSandboxConfiguration config = configuration(IMAGE, OptionalInt.empty());
        assertThat(config.configurationDigest().value()).isNotBlank();
        DockerAioSandboxConfiguration ephemeral = new DockerAioSandboxConfiguration(
                config.installationId(),
                config.dockerSocket(),
                config.image(),
                config.containerWorkspaceRoot(),
                config.runtimes(),
                config.uid(),
                config.gid(),
                config.nanoCpus(),
                config.memoryBytes(),
                config.pidsLimit(),
                config.engineTimeout(),
                config.stopTimeout(),
                config.aioPort(),
                config.maxConcurrentSessions(),
                DockerAioLifecycleMode.EPHEMERAL,
                config.idleTimeout());
        assertThat(config.configurationDigest()).isNotEqualTo(ephemeral.configurationDigest());
    }

    @Test
    void imageMustBeDigestLocked() {
        assertThatThrownBy(() -> configuration("ghcr.io/example/sandbox:latest", OptionalInt.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image");
    }

    @Test
    void uidAndGidMustBeDeclaredTogether() {
        assertThatThrownBy(() -> new DockerAioSandboxConfiguration(
                        "install",
                        Path.of("docker.sock").toAbsolutePath(),
                        IMAGE,
                        "/workspace",
                        Map.of(),
                        OptionalInt.of(1000),
                        OptionalInt.empty(),
                        1_000_000_000L,
                        256L * 1024 * 1024,
                        128,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(2),
                        8080))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uid and gid");
    }

    @Test
    void runtimePathMustBeAbsoluteInTheImage() {
        assertThatThrownBy(() -> new DockerAioSandboxConfiguration(
                        "install",
                        Path.of("docker.sock").toAbsolutePath(),
                        IMAGE,
                        "/workspace",
                        Map.of("bash", "bin/bash"),
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        1_000_000_000L,
                        256L * 1024 * 1024,
                        128,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(2),
                        8080))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtime executable");
    }

    private static DockerAioSandboxConfiguration configuration(String image, OptionalInt uid) {
        return configuration(image, uid, Path.of("docker.sock").toAbsolutePath());
    }

    private static DockerAioSandboxConfiguration configuration(String image, OptionalInt uid, Path socket) {
        return new DockerAioSandboxConfiguration(
                "install",
                socket,
                image,
                "/workspace",
                Map.of("bash", "/bin/bash"),
                uid,
                uid.isPresent() ? OptionalInt.of(1000) : OptionalInt.empty(),
                1_000_000_000L,
                256L * 1024 * 1024,
                128,
                Duration.ofSeconds(5),
                Duration.ofSeconds(2),
                8080);
    }
}
