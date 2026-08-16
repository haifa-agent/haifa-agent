package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomousDeliveryLiveBudgetTest {
    @Test
    void authorizesOnlyAnExplicitCeilingThatContainsTheWorstCaseTokenPriceBound() {
        AutonomousDeliveryLiveBudget.Authorization authorization =
                AutonomousDeliveryLiveBudget.authorize(suite(1000, 1_000_000, 1_000_000), 1000);

        assertEquals("CNY", authorization.currency());
        assertEquals(600, authorization.worstCaseCostMinorUnits());
        assertEquals(1000, authorization.approvedCeilingMinorUnits());
        assertThrows(
                IllegalArgumentException.class,
                () -> AutonomousDeliveryLiveBudget.authorize(suite(1000, 1_000_000, 1_000_000), 999));
        assertThrows(
                IllegalArgumentException.class,
                () -> AutonomousDeliveryLiveBudget.authorize(suite(500, 1_000_000, 1_000_000), 1000));
    }

    @Test
    void estimatesCacheMissCostWithoutClaimingProviderReportedCost() {
        AutonomousDeliverySuiteManifest suite = suite(1000, 1_000_000, 1_000_000);
        AutonomousDeliveryLiveBudget.Authorization authorization = AutonomousDeliveryLiveBudget.authorize(suite, 1000);

        AutonomousDeliveryLiveBudget.Evidence evidence = AutonomousDeliveryLiveBudget.evidence(
                suite,
                authorization,
                List.of(
                        Map.of("inputTokens", 100_000, "outputTokens", 20_000),
                        Map.of("inputTokens", 50_000, "outputTokens", 10_000)),
                1000);

        assertTrue(evidence.passed());
        assertEquals(21, evidence.estimatedCostMinorUnits());
        assertEquals(false, evidence.artifact().get("providerReportedCostKnown"));

        AutonomousDeliveryLiveBudget.Evidence overtime = AutonomousDeliveryLiveBudget.evidence(
                suite, authorization, List.of(Map.of("inputTokens", 1, "outputTokens", 1)), 3_000_001);
        assertFalse(overtime.passed());
    }

    private static AutonomousDeliverySuiteManifest suite(
            long ceilingMinorUnits, long maxInputTokens, long maxOutputTokens) {
        return new AutonomousDeliverySuiteManifest(
                1,
                "bring-up-v1",
                AutonomousDeliveryCaseCatalog.EXPECTED_CATALOG_ID,
                "PHASE_1",
                "autonomous-delivery-v1",
                null,
                null,
                new AutonomousDeliverySuiteManifest.Budget(
                        1_200_000,
                        50,
                        64,
                        64,
                        1,
                        3_000_000,
                        "CNY",
                        ceilingMinorUnits,
                        100,
                        200,
                        maxInputTokens,
                        maxOutputTokens),
                List.of(
                        new AutonomousDeliverySuiteManifest.CaseSelection("04", 1, true),
                        new AutonomousDeliverySuiteManifest.CaseSelection("07", 1, true)));
    }
}
