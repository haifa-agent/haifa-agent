package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.haifa.agent.testing.repository.RepositoryRevision;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutonomousDeliveryExecutionPlanTest {
    private static final RepositoryRevision PRODUCT = new RepositoryRevision("1".repeat(40), false);
    private static final RepositoryRevision CONFIG = new RepositoryRevision("2".repeat(40), false);

    @Test
    void fingerprintIsDeterministicAndRevisionBound() {
        AutonomousDeliveryCaseCatalog catalog = AutonomousDeliveryCaseCatalog.loadVerified();
        AutonomousDeliveryExecutionPlan.Frozen first =
                AutonomousDeliveryExecutionPlan.freeze(catalog, suite(), matrix(), combination(), PRODUCT, CONFIG);
        AutonomousDeliveryExecutionPlan.Frozen second =
                AutonomousDeliveryExecutionPlan.freeze(catalog, suite(), matrix(), combination(), PRODUCT, CONFIG);
        AutonomousDeliveryExecutionPlan.Frozen changed = AutonomousDeliveryExecutionPlan.freeze(
                catalog, suite(), matrix(), combination(), new RepositoryRevision("3".repeat(40), false), CONFIG);

        assertEquals(first.sha256(), second.sha256());
        assertEquals(64, first.sha256().length());
        assertNotEquals(first.sha256(), changed.sha256());
    }

    private static AutonomousDeliverySuiteManifest suite() {
        return new AutonomousDeliverySuiteManifest(
                1,
                "bring-up-v1",
                AutonomousDeliveryCaseCatalog.EXPECTED_CATALOG_ID,
                "PHASE_1",
                "autonomous-delivery-v1",
                null,
                null,
                new AutonomousDeliverySuiteManifest.Budget(
                        1_200_000, 50, 64, 64, 1, 3_000_000, "CNY", 1000, 100, 200, 1_000_000, 1_000_000),
                List.of(
                        new AutonomousDeliverySuiteManifest.CaseSelection("04", 1, true),
                        new AutonomousDeliverySuiteManifest.CaseSelection("07", 1, true)));
    }

    private static AutonomousDeliveryMatrixManifest matrix() {
        return new AutonomousDeliveryMatrixManifest(
                1, "autonomous-delivery-v1", PRODUCT.commit(), "explicit", List.of(combination()));
    }

    private static AutonomousDeliveryMatrixManifest.Combination combination() {
        return new AutonomousDeliveryMatrixManifest.Combination(
                "windows-deepseek-host-trusted",
                "windows",
                "deepseek",
                "deepseek-v4-flash",
                "conpty",
                "host-guarded",
                "allow",
                "powershell",
                "TRUSTED_HOST_ONLY",
                "windows-host-trusted-v1",
                1);
    }
}
