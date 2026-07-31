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
    void rejectsInvalidTestingAssetInventoryBeforePlanning() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path configRoot = Files.createDirectory(temporaryDirectory.resolve("test-config"));
        Path productInventory = projectRoot.resolve("haifa-agent-testing/testing-assets-v1.json");
        Files.createDirectories(productInventory.getParent());
        Files.writeString(
                productInventory,
                """
                {
                  "schemaVersion": 1,
                  "repositoryId": "haifa-agent",
                  "coverageRoots": [],
                  "assets": [
                    {
                      "assetId": "inventory",
                      "path": "haifa-agent-testing/testing-assets-v1.json",
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
        Path inventory = configRoot.resolve("assets/testing-assets-v1.json");
        Files.createDirectories(inventory.getParent());
        Files.writeString(
                inventory,
                """
                {
                  "schemaVersion": 1,
                  "repositoryId": "haifa-agent-test-config",
                  "coverageRoots": [],
                  "assets": [
                    {
                      "assetId": "missing",
                      "path": "missing.txt",
                      "kind": "FIXTURE",
                      "lifecycle": "ACTIVE",
                      "disposition": "KEEP",
                      "owner": "testing-platform",
                      "referencedBy": [],
                      "replacement": "",
                      "rationale": "must fail before suite loading"
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
