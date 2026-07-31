package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutonomousDeliveryHarnessMainTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsUninventoriedAssetBeforePlanning() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path configRoot = Files.createDirectory(temporaryDirectory.resolve("test-config"));
        Path productInventory = projectRoot.resolve("haifa-agent-testing/testing-assets-v2.json");
        Files.createDirectories(productInventory.getParent());
        Files.writeString(
                productInventory,
                """
                {
                  "schemaVersion": 2,
                  "repositoryId": "haifa-agent",
                  "coverageRoots": [],
                  "assets": [
                    {
                      "assetId": "inventory",
                      "path": "haifa-agent-testing/testing-assets-v2.json",
                      "kind": "MANIFEST",
                      "lifecycle": "ACTIVE",
                      "disposition": "KEEP",
                      "owner": "testing-platform",
                      "referencedBy": [],
                      "replacement": "",
                      "rationale": "valid product inventory"
                    }
                  ]
                }
                """);
        Path inventory = configRoot.resolve("assets/testing-assets-v2.json");
        Files.createDirectories(inventory.getParent());
        Files.writeString(configRoot.resolve("assets/orphan.txt"), "orphan");
        Files.writeString(
                inventory,
                """
                {
                  "schemaVersion": 2,
                  "repositoryId": "haifa-agent-test-config",
                  "coverageRoots": ["assets"],
                  "assets": [
                    {
                      "assetId": "inventory",
                      "path": "assets/testing-assets-v2.json",
                      "kind": "MANIFEST",
                      "lifecycle": "ACTIVE",
                      "disposition": "KEEP",
                      "owner": "testing-platform",
                      "referencedBy": [],
                      "replacement": "",
                      "rationale": "formal preflight inventory",
                      "coverageMode": "EXACT"
                    },
                    {
                      "assetId": "asset-root",
                      "path": "assets",
                      "kind": "DIRECTORY",
                      "lifecycle": "ACTIVE",
                      "disposition": "KEEP",
                      "owner": "testing-platform",
                      "referencedBy": ["assets/testing-assets-v2.json"],
                      "replacement": "",
                      "rationale": "directory lifecycle only",
                      "coverageMode": "EXACT"
                    }
                  ]
                }
                """);
        AutonomousDeliveryHarnessMain.Options options = new AutonomousDeliveryHarnessMain.Options(
                "plan",
                projectRoot,
                configRoot,
                null,
                null,
                null,
                null,
                "unused-combination",
                List.of(),
                false,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertThrows(IllegalArgumentException.class, () -> new AutonomousDeliveryHarnessMain().run(options));
    }
}
