package io.haifa.agent.testing.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeRunRootTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsRunLocationsThatContainOrAreContainedByRepositories() throws Exception {
        Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
        Path nested = Files.createDirectory(repository.resolve("runs"));

        assertThrows(
                IllegalArgumentException.class,
                () -> SafeRunRoot.requireExternalLocation(nested, List.of(repository), "run root"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SafeRunRoot.requireExternalLocation(temporaryDirectory, List.of(nested), "run root"));
    }

    @Test
    void acceptsAndNormalizesANewRepositoryExternalLocation() throws Exception {
        Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
        Path requested = temporaryDirectory.resolve("evidence").resolve("..").resolve("runs");

        Path resolved = SafeRunRoot.requireExternalLocation(requested, List.of(repository), "run root");

        assertEquals(temporaryDirectory.toRealPath().resolve("runs"), resolved);
    }

    @Test
    void existingParentRequiresAnExistingDirectory() throws Exception {
        Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
        Path safeParent = Files.createDirectory(temporaryDirectory.resolve("safe-parent"));

        assertThrows(
                IllegalArgumentException.class,
                () -> SafeRunRoot.requireExternalExistingParent(
                        temporaryDirectory.resolve("missing"), List.of(repository), "run parent"));
        assertEquals(
                safeParent.toRealPath(),
                SafeRunRoot.requireExternalExistingParent(safeParent, List.of(repository), "run parent"));
    }
}
