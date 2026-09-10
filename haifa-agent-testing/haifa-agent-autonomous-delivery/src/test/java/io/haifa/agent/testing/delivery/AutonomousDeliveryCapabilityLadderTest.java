package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
        org.junit.jupiter.api.Assertions.assertNotNull(catalog.require("01"), "Case 01 must exist in catalog");
    }
}
