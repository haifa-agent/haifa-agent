package io.haifa.agent.testing.assets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestingAssetPreflightTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesBothRepositoryInventoriesWhenPresent() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path configRoot = Files.createDirectory(temporaryDirectory.resolve("test-config"));
        writeInventory(
                projectRoot.resolve("haifa-agent-testing/testing-assets-v2.json"),
                "haifa-agent",
                "haifa-agent-testing/testing-assets-v2.json");
        writeInventory(
                configRoot.resolve("assets/testing-assets-v2.json"),
                "haifa-agent-test-config",
                "assets/testing-assets-v2.json");

        assertDoesNotThrow(() -> new TestingAssetPreflight().validate(projectRoot, configRoot));
    }

    @Test
    void rejectsARepositoryWithoutItsRequiredInventory() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path configRoot = Files.createDirectory(temporaryDirectory.resolve("test-config"));
        writeInventory(
                projectRoot.resolve("haifa-agent-testing/testing-assets-v2.json"),
                "haifa-agent",
                "haifa-agent-testing/testing-assets-v2.json");

        assertThrows(
                IllegalArgumentException.class, () -> new TestingAssetPreflight().validate(projectRoot, configRoot));
    }

    @Test
    void rejectsAnInvalidPrivateInventoryBeforeSuiteLoading() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path configRoot = Files.createDirectory(temporaryDirectory.resolve("test-config"));
        writeInventory(
                projectRoot.resolve("haifa-agent-testing/testing-assets-v2.json"),
                "haifa-agent",
                "haifa-agent-testing/testing-assets-v2.json");
        writeInventory(
                configRoot.resolve("assets/testing-assets-v2.json"),
                "haifa-agent-test-config",
                "missing-active-asset.txt");

        assertThrows(
                IllegalArgumentException.class, () -> new TestingAssetPreflight().validate(projectRoot, configRoot));
    }

    private static void writeInventory(Path path, String repositoryId, String assetPath) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                """
                {
                  "schemaVersion": 2,
                  "repositoryId": "%s",
                  "coverageRoots": [],
                  "assets": [
                    {
                      "assetId": "asset",
                      "path": "%s",
                      "kind": "MANIFEST",
                      "lifecycle": "ACTIVE",
                      "disposition": "KEEP",
                      "owner": "testing-platform",
                      "referencedBy": [],
                      "replacement": "",
                      "rationale": "shared entry-point preflight"
                    }
                  ]
                }
                """
                        .formatted(repositoryId, assetPath));
    }
}
