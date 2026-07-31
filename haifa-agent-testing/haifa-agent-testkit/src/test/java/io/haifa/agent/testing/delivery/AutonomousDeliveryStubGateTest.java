package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class AutonomousDeliveryStubGateTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void deletesGitStyleReadOnlyFilesFromDriverWorkspace() throws Exception {
        Path workspace = Files.createDirectories(temporaryDirectory.resolve("workspace/.git/objects/ab"));
        Path object = Files.writeString(workspace.resolve("object"), "fixture");
        Files.getFileAttributeView(object, DosFileAttributeView.class).setReadOnly(true);

        AutonomousDeliveryStubGate.deleteTree(temporaryDirectory.resolve("workspace"));

        assertFalse(Files.exists(temporaryDirectory.resolve("workspace")));
    }
}
