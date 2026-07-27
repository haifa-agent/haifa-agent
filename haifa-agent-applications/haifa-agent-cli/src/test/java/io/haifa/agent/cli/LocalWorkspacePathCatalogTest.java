package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalWorkspacePathCatalogTest {
    @TempDir
    Path workspace;

    @Test
    void listsOnlySafeSourceFilesForAtFileCompletion() throws Exception {
        Files.createDirectories(workspace.resolve("src/main"));
        Files.writeString(workspace.resolve("README.md"), "readme");
        Files.writeString(workspace.resolve("src/main/App.java"), "class App {}");
        Files.writeString(workspace.resolve(".env"), "SECRET=value");
        Files.createDirectories(workspace.resolve(".git"));
        Files.writeString(workspace.resolve(".git/config"), "private");
        Files.createDirectories(workspace.resolve("target"));
        Files.writeString(workspace.resolve("target/generated.txt"), "generated");

        assertThat(new LocalWorkspacePathCatalog(workspace).list()).containsExactly("README.md", "src/main/App.java");
    }
}
