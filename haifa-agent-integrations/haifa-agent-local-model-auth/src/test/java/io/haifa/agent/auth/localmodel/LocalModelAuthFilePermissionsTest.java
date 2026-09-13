package io.haifa.agent.auth.localmodel;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalModelAuthFilePermissionsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsSymbolicLinksWhenThePlatformCanCreateThem() throws Exception {
        Path target = Files.writeString(temporaryDirectory.resolve("target"), "secret");
        Path link = temporaryDirectory.resolve("auth.json");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.assumeTrue(false, "symbolic links unavailable on this host");
        }

        assertThatThrownBy(() -> new LocalModelAuthFilePermissions().rejectSymbolicLink(link))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("symbolic link");
    }
}
