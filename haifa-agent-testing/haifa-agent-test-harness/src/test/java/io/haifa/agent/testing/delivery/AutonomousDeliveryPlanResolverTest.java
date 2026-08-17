package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.haifa.agent.testing.harness.PlatformManifest;
import io.haifa.agent.testing.harness.ResolvedTestPlan;
import io.haifa.agent.testing.repository.RepositoryRevision;
import io.haifa.agent.testing.suite.AgentProfileManifest;
import io.haifa.agent.testing.suite.ResolvedAgentProfile;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutonomousDeliveryPlanResolverTest {
    private static final RepositoryRevision PRODUCT = new RepositoryRevision("1".repeat(40), false);
    private static final RepositoryRevision CONFIG = new RepositoryRevision("2".repeat(40), false);

    @Test
    void fingerprintIsDeterministicAndRevisionBound() {
        AutonomousDeliveryCaseCatalog catalog = AutonomousDeliveryCaseCatalog.loadVerified();
        ResolvedTestPlan first = AutonomousDeliveryPlanResolver.resolve(
                catalog, suite(), matrix(), combination(), profile(), PRODUCT, CONFIG, "f".repeat(64));
        ResolvedTestPlan second = AutonomousDeliveryPlanResolver.resolve(
                catalog, suite(), matrix(), combination(), profile(), PRODUCT, CONFIG, "f".repeat(64));
        ResolvedTestPlan changed = AutonomousDeliveryPlanResolver.resolve(
                catalog,
                suite(),
                matrix(),
                combination(),
                profile(),
                new RepositoryRevision("3".repeat(40), false),
                CONFIG,
                "f".repeat(64));

        assertEquals(first.sha256(), second.sha256());
        assertEquals(64, first.sha256().length());
        assertNotEquals(first.sha256(), changed.sha256());
    }

    @Test
    void liveExecutionRequiresTheExactApprovedFingerprint() {
        ResolvedTestPlan plan = AutonomousDeliveryPlanResolver.resolve(
                AutonomousDeliveryCaseCatalog.loadVerified(),
                suite(),
                matrix(),
                combination(),
                profile(),
                PRODUCT,
                CONFIG,
                "f".repeat(64));

        plan.requireApproved(plan.sha256());
        assertThrows(IllegalArgumentException.class, () -> plan.requireApproved(null));
        assertThrows(IllegalArgumentException.class, () -> plan.requireApproved("f".repeat(64)));
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

    private static PlatformManifest matrix() {
        return new PlatformManifest(2, "autonomous-delivery-v1", "explicit", List.of(combination()));
    }

    private static PlatformManifest.PlatformProfile combination() {
        return new PlatformManifest.PlatformProfile(
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
                List.of("TEST_API_KEY"),
                List.of("TEST_API_KEY"));
    }
}
