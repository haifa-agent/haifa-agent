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
        return new DockerAioSandboxConfiguration(
                "install",
                Path.of("docker.sock").toAbsolutePath(),
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
