package io.haifa.agent.sandbox.localnative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalNativeSandboxConfigurationTest {
    @TempDir
    Path temporary;

    @Test
    void freezesStableConfigurationDigestAndResolvesNamedPathPolicies() {
        Path cache = temporary.resolve("cache");
        LocalNativeSandboxConfiguration configuration = configuration(
                Map.of("build-cache", new LocalNativePathGrant(cache, false)), Set.of(temporary.resolve("sensitive")));

        assertThat(configuration.digest()).isEqualTo(configuration.digest());
        assertThat(configuration.resolveAdditionalPaths(Set.of("build-cache")))
                .containsExactly(new LocalNativePathGrant(cache, false));
        assertThatThrownBy(() -> configuration.resolveAdditionalPaths(Set.of("unknown")))
                .isInstanceOf(LocalNativeSandboxException.class)
                .extracting(exception -> ((LocalNativeSandboxException) exception).code())
                .isEqualTo("WORKSPACE_BIND_FAILED");
    }

    @Test
    void rejectsFilesystemRootsAndSensitivePathOverlap() {
        assertThatThrownBy(() ->
                        new LocalNativePathGrant(Path.of("").toAbsolutePath().getRoot(), true))
                .isInstanceOf(IllegalArgumentException.class);

        Path sensitive = temporary.resolve("private");
        assertThatThrownBy(() -> configuration(
                        Map.of("private-child", new LocalNativePathGrant(sensitive.resolve("child"), true)),
                        Set.of(sensitive)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sensitive");
    }

    private LocalNativeSandboxConfiguration configuration(
            Map<String, LocalNativePathGrant> paths, Set<Path> sensitive) {
        return new LocalNativeSandboxConfiguration(
                List.of("/bin/bash", "-lc"),
                temporary.resolve("controls"),
                temporary.resolve("sandbox-exec"),
                temporary.resolve("bwrap"),
                paths,
                sensitive);
    }
}
