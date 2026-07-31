package io.haifa.agent.testing.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MatrixManifestLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsAndSelectsOneExplicitCombination() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("matrices"));
        Files.writeString(temporaryDirectory.resolve("matrices/primary-v1.yaml"), validMatrix());

        MatrixManifest matrix = new MatrixManifestLoader().load(temporaryDirectory, "primary-v1");

        assertEquals("windows", matrix.requireCombination("windows-primary").platform());
        assertThrows(IllegalArgumentException.class, () -> matrix.requireCombination("missing"));
    }

    @Test
    void rejectsDuplicateCombinationIds() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("matrices"));
        Files.writeString(
                temporaryDirectory.resolve("matrices/primary-v1.yaml"),
                validMatrix().replace("linux-primary", "windows-primary"));

        assertThrows(IllegalArgumentException.class, () -> new MatrixManifestLoader()
                .load(temporaryDirectory, "primary-v1"));
    }

    private static String validMatrix() {
        return """
                schemaVersion: 1
                matrixId: primary-v1
                strategy: explicit
                combinations:
                  - id: linux-primary
                    platform: linux
                    modelProvider: deepseek
                    modelId: deepseek-v4-pro
                    webProvider: aliyun
                    mcpTarget: utility
                  - id: windows-primary
                    platform: windows
                    modelProvider: deepseek
                    modelId: deepseek-v4-pro
                    webProvider: aliyun
                    mcpTarget: utility
                """;
    }
}
