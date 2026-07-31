package io.haifa.agent.testing.assets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Runs the shared public/private testing asset lifecycle check for every suite entry point. */
public final class TestingAssetPreflight {
    private final TestingAssetInventoryValidator inventoryValidator = new TestingAssetInventoryValidator();

    public void validate(Path projectRoot, Path configRoot) throws IOException {
        Path product = Objects.requireNonNull(projectRoot, "projectRoot must not be null")
                .toAbsolutePath()
                .normalize();
        Path configuration = Objects.requireNonNull(configRoot, "configRoot must not be null")
                .toAbsolutePath()
                .normalize();
        Path productInventory = product.resolve("haifa-agent-testing/testing-assets-v1.json");
        Path configurationInventory = configuration.resolve("assets/testing-assets-v1.json");
        requireInventory(productInventory, "product");
        requireInventory(configurationInventory, "test-config");
        inventoryValidator.validateIfPresent(product, productInventory);
        inventoryValidator.validateIfPresent(configuration, configurationInventory);
    }

    private static void requireInventory(Path inventory, String repository) {
        if (!Files.isRegularFile(inventory)) {
            throw new IllegalArgumentException(repository + " testing asset inventory is required: " + inventory);
        }
    }
}
