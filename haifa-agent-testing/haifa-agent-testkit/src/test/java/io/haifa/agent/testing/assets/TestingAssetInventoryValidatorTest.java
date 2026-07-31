package io.haifa.agent.testing.assets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestingAssetInventoryValidatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesActiveDeprecatedRemovedAndCoveredAssets() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("assets"));
        Files.writeString(temporaryDirectory.resolve("assets/active.txt"), "active");
        Files.writeString(temporaryDirectory.resolve("assets/deprecated.txt"), "deprecated");
        Path inventory = writeInventory(
                """
                {
                  "schemaVersion": 1,
                  "repositoryId": "fixture",
                  "coverageRoots": ["assets"],
                  "assets": [
                    {
                      "assetId": "inventory",
                      "path": "inventory.json",
                      "kind": "MANIFEST",
                      "lifecycle": "ACTIVE",
                      "disposition": "KEEP",
                      "owner": "testing",
                      "referencedBy": [],
                      "replacement": "",
                      "rationale": "inventory"
                    },
                    {
                      "assetId": "active",
                      "path": "assets/active.txt",
                      "kind": "FIXTURE",
                      "lifecycle": "ACTIVE",
                      "disposition": "KEEP",
                      "owner": "testing",
                      "referencedBy": ["inventory.json"],
                      "replacement": "",
                      "rationale": "active fixture"
                    },
                    {
                      "assetId": "deprecated",
                      "path": "assets/deprecated.txt",
                      "kind": "FIXTURE",
                      "lifecycle": "DEPRECATED",
                      "disposition": "DELETE",
                      "owner": "testing",
                      "referencedBy": [],
                      "replacement": "",
                      "rationale": "obsolete fixture"
                    },
                    {
                      "assetId": "removed",
                      "path": "assets/removed.txt",
                      "kind": "FIXTURE",
                      "lifecycle": "REMOVED",
                      "disposition": "DELETE",
                      "owner": "testing",
                      "referencedBy": [],
                      "replacement": "",
                      "rationale": "removed fixture"
                    }
                  ]
                }
                """);

        assertDoesNotThrow(() -> new TestingAssetInventoryValidator().validateIfPresent(temporaryDirectory, inventory));
    }

    @Test
    void rejectsUninventoriedFilesInsideCoverageRoot() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("assets"));
        Files.writeString(temporaryDirectory.resolve("assets/orphan.txt"), "orphan");
        Path inventory = writeInventory(singleInventoryAsset());

        assertThrows(IllegalArgumentException.class, () -> new TestingAssetInventoryValidator()
                .validateIfPresent(temporaryDirectory, inventory));
    }

    @Test
    void rejectsRemovedAssetThatStillExists() throws Exception {
        Files.writeString(temporaryDirectory.resolve("removed.txt"), "still here");
        Path inventory = writeInventory(
                """
                {
                  "schemaVersion": 1,
                  "repositoryId": "fixture",
                  "coverageRoots": [],
                  "assets": [
                    {
                      "assetId": "inventory",
                      "path": "inventory.json",
                      "kind": "MANIFEST",
                      "lifecycle": "ACTIVE",
                      "disposition": "KEEP",
                      "owner": "testing",
                      "referencedBy": [],
                      "replacement": "",
                      "rationale": "inventory"
                    },
                    {
                      "assetId": "removed",
                      "path": "removed.txt",
                      "kind": "FIXTURE",
                      "lifecycle": "REMOVED",
                      "disposition": "DELETE",
                      "owner": "testing",
                      "referencedBy": [],
                      "replacement": "",
                      "rationale": "removed fixture"
                    }
                  ]
                }
                """);

        assertThrows(IllegalArgumentException.class, () -> new TestingAssetInventoryValidator()
                .validateIfPresent(temporaryDirectory, inventory));
    }

    @Test
    void treatsUntrackedEmptyDirectoryTreeAsRemoved() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("removed/empty/children"));
        Path inventory = writeInventory(
                """
                {
                  "schemaVersion": 1,
                  "repositoryId": "fixture",
                  "coverageRoots": [],
                  "assets": [
                    {
                      "assetId": "inventory",
                      "path": "inventory.json",
                      "kind": "MANIFEST",
                      "lifecycle": "ACTIVE",
                      "disposition": "KEEP",
                      "owner": "testing",
                      "referencedBy": [],
                      "replacement": "",
                      "rationale": "inventory"
                    },
                    {
                      "assetId": "removed-directory",
                      "path": "removed",
                      "kind": "DIRECTORY",
                      "lifecycle": "REMOVED",
                      "disposition": "DELETE",
                      "owner": "testing",
                      "referencedBy": [],
                      "replacement": "",
                      "rationale": "removed directory"
                    }
                  ]
                }
                """);

        assertDoesNotThrow(() -> new TestingAssetInventoryValidator().validateIfPresent(temporaryDirectory, inventory));
    }

    @Test
    void rejectsRepositoryEscape() throws Exception {
        Path inventory = writeInventory(
                """
                {
                  "schemaVersion": 1,
                  "repositoryId": "fixture",
                  "coverageRoots": [],
                  "assets": [
                    {
                      "assetId": "escape",
                      "path": "../escape.txt",
                      "kind": "FIXTURE",
                      "lifecycle": "DEPRECATED",
                      "disposition": "DELETE",
                      "owner": "testing",
                      "referencedBy": [],
                      "replacement": "",
                      "rationale": "invalid path"
                    }
                  ]
                }
                """);

        assertThrows(IllegalArgumentException.class, () -> new TestingAssetInventoryValidator()
                .validateIfPresent(temporaryDirectory, inventory));
    }

    private Path writeInventory(String value) throws Exception {
        Path inventory = temporaryDirectory.resolve("inventory.json");
        Files.writeString(inventory, value);
        return inventory;
    }

    private static String singleInventoryAsset() {
        return """
                {
                  "schemaVersion": 1,
                  "repositoryId": "fixture",
                  "coverageRoots": ["assets"],
                  "assets": [
                    {
                      "assetId": "inventory",
                      "path": "inventory.json",
                      "kind": "MANIFEST",
                      "lifecycle": "ACTIVE",
                      "disposition": "KEEP",
                      "owner": "testing",
                      "referencedBy": [],
                      "replacement": "",
                      "rationale": "inventory"
                    }
                  ]
                }
                """;
    }
}
