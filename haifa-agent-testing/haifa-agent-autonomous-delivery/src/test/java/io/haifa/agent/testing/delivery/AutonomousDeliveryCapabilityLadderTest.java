package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AutonomousDeliveryCapabilityLadderTest {
    @Test
    void capabilityLadderSpecAndDocumentationArePresent() {
        Path moduleRoot = Path.of(System.getProperty("basedir", "."));
        Path specFile = moduleRoot.resolve("AUTONOMOUS_DELIVERY_LADDER_SPEC.md");
        Path readmeFile = moduleRoot.resolve("README.md");

        assertTrue(Files.isRegularFile(specFile), "AUTONOMOUS_DELIVERY_LADDER_SPEC.md must exist in module root");
        assertTrue(Files.isRegularFile(readmeFile), "README.md must exist in module root");
    }

    @Test
    void fixtureStoreAndCaseCatalogRemainAccessible() {
        AutonomousDeliveryFixtureStore store = new AutonomousDeliveryFixtureStore();
        AutonomousDeliveryCaseCatalog catalog = AutonomousDeliveryCaseCatalog.loadVerified(store);

        assertFalse(catalog.cases().isEmpty(), "Historical case catalog must be loadable from fixtures");
        assertNotNull(catalog.require("01"), "Case 01 must exist in catalog");
    }

    @Test
    void assetLockPinsAnImmutableExternalAssetRevision() throws Exception {
        Path moduleRoot = Path.of(System.getProperty("basedir", "."));
        String lock = Files.readString(moduleRoot.resolve("assets.lock.json"));

        assertTrue(lock.contains("\"schemaVersion\": 1"));
        assertTrue(lock.contains("git@github.com:haifa-agent/haifa-agent-autonomous-delivery-assets.git"));
        assertTrue(
                Pattern.compile("\"revision\": \"[0-9a-f]{40}\"").matcher(lock).find());
        assertTrue(Pattern.compile("\"manifestSha256\": \"[0-9a-f]{64}\"")
                .matcher(lock)
                .find());
        assertFalse(
                Files.exists(moduleRoot.resolve("cases")), "large authored assets must stay outside the Maven module");
    }
}
