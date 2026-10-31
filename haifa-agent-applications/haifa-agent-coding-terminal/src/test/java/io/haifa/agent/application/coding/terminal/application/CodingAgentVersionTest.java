package io.haifa.agent.application.coding.terminal.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodingAgentVersionTest {
    @TempDir
    Path temp;

    @Test
    void readsThePackagedVersionKeyThroughTheClassLoader() throws Exception {
        Path root = resource("version=feat-opencode-go-login-d67c751\n");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            assertThat(CodingAgentVersion.read(loader)).isEqualTo("feat-opencode-go-login-d67c751");
        }
    }

    @Test
    void ignoresMissingBlankAndUnsafeValues() throws Exception {
        try (URLClassLoader empty = new URLClassLoader(new URL[] {temp.toUri().toURL()}, null)) {
            assertThat(CodingAgentVersion.read(empty)).isNull();
        }
        try (URLClassLoader blank =
                new URLClassLoader(new URL[] {resource("version=   \n").toUri().toURL()}, null)) {
            assertThat(CodingAgentVersion.read(blank)).isNull();
        }
        try (URLClassLoader unsafe = new URLClassLoader(
                new URL[] {resource("version=bad value!\n").toUri().toURL()}, null)) {
            assertThat(CodingAgentVersion.read(unsafe)).isNull();
        }
        assertThat(CodingAgentVersion.normalize(null)).isNull();
        assertThat(CodingAgentVersion.normalize("x".repeat(65))).isNull();
        assertThat(CodingAgentVersion.normalize(" 0.1.5-SNAPSHOT ")).isEqualTo("0.1.5-SNAPSHOT");
    }

    @Test
    void packagedResourceCarriesTheFilteredPomVersion() {
        String current = CodingAgentVersion.current();

        assertThat(current).matches("[A-Za-z0-9._+-]{1,64}");
        assertThat(current).doesNotContain("${");
    }

    private Path resource(String content) throws Exception {
        Path root = Files.createTempDirectory(temp, "version-");
        Path file = root.resolve(CodingAgentVersion.RESOURCE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return root;
    }
}
