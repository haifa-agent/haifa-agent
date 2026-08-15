package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.haifa.agent.testing.repository.RepositoryRevision;
import io.haifa.agent.testing.suite.AgentProfileManifest;
import io.haifa.agent.testing.suite.ResolvedAgentProfile;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutonomousDeliveryExecutionPlanTest {
    private static final RepositoryRevision PRODUCT = new RepositoryRevision("1".repeat(40), false);
    private static final RepositoryRevision CONFIG = new RepositoryRevision("2".repeat(40), false);

    @Test
    void fingerprintIsDeterministicAndRevisionBound() {
        AutonomousDeliveryCaseCatalog catalog = AutonomousDeliveryCaseCatalog.loadVerified();
        AutonomousDeliveryExecutionPlan.Frozen first = AutonomousDeliveryExecutionPlan.freeze(
                catalog, suite(), matrix(), combination(), profile(), PRODUCT, CONFIG);
        AutonomousDeliveryExecutionPlan.Frozen second = AutonomousDeliveryExecutionPlan.freeze(
                catalog, suite(), matrix(), combination(), profile(), PRODUCT, CONFIG);
        AutonomousDeliveryExecutionPlan.Frozen changed = AutonomousDeliveryExecutionPlan.freeze(
                catalog,
                suite(),
                matrix(),
                combination(),
                profile(),
                new RepositoryRevision("3".repeat(40), false),
                CONFIG);

        assertEquals(first.sha256(), second.sha256());
        assertEquals(64, first.sha256().length());
        assertNotEquals(first.sha256(), changed.sha256());
    }

    @Test
    void liveExecutionRequiresTheExactApprovedFingerprint() {
        AutonomousDeliveryExecutionPlan.Frozen plan = AutonomousDeliveryExecutionPlan.freeze(
                AutonomousDeliveryCaseCatalog.loadVerified(),
                suite(),
                matrix(),
                combination(),
                profile(),
                PRODUCT,
                CONFIG);

        AutonomousDeliveryExecutionPlan.requireApproved(plan, plan.sha256());
        assertThrows(IllegalArgumentException.class, () -> AutonomousDeliveryExecutionPlan.requireApproved(plan, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> AutonomousDeliveryExecutionPlan.requireApproved(plan, "f".repeat(64)));
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
        return new AutonomousDeliveryMatrixManifest(2, "autonomous-delivery-v1", "explicit", List.of(combination()));
    }

    private static AutonomousDeliveryMatrixManifest.Combination combination() {
        return new AutonomousDeliveryMatrixManifest.Combination(
                "windows-host-trusted",
                "windows",
                "conpty",
                "host-guarded",
                "allow",
                "powershell",
                "TRUSTED_HOST_ONLY",
                "windows-host-trusted-v1",
                1);
    }

    private static ResolvedAgentProfile profile() {
        return new ResolvedAgentProfile(
                new AgentProfileManifest(
                        1, "standard-client", PRODUCT.commit(), "agents/standard.yaml", "a".repeat(64)),
                Path.of("agents/standard.yaml"),
                "b".repeat(64),
                List.of("TEST_API_KEY"));
    }
}
