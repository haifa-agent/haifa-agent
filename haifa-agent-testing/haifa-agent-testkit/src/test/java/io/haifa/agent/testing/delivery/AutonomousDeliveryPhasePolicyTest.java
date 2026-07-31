package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomousDeliveryPhasePolicyTest {
    @Test
    void resolvesDistinctPhaseRequirements() {
        AutonomousDeliveryPhasePolicy phaseOne = AutonomousDeliveryPhasePolicy.resolve(suite("PHASE_1", null));
        AutonomousDeliveryPhasePolicy phaseTwo = AutonomousDeliveryPhasePolicy.resolve(
                suite("PHASE_2", AutonomousDeliveryPhasePolicy.REVIEWED_READ_ONLY_ANALYZE_STUB));
        AutonomousDeliveryPhasePolicy phaseThree = AutonomousDeliveryPhasePolicy.resolve(suite("PHASE_3", null));

        assertEquals(1, phaseOne.phaseNumber());
        assertFalse(phaseOne.requiresDeterministicAnalyze());
        assertFalse(phaseOne.requiresDeterministicReplay());
        assertFalse(phaseOne.requiresExternalVerification());
        assertEquals(2, phaseTwo.phaseNumber());
        assertTrue(phaseTwo.requiresDeterministicAnalyze());
        assertFalse(phaseTwo.requiresExternalVerification());
        assertEquals(3, phaseThree.phaseNumber());
        assertTrue(phaseThree.requiresDeterministicReplay());
        assertTrue(phaseThree.requiresExternalVerification());
    }

    @Test
    void phaseTwoRequiresReviewedAnalyzeStubBeforeExecution() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AutonomousDeliveryPhasePolicy.resolve(suite("PHASE_2", "unreviewed-stub")));
    }

    @Test
    void requiredDeterministicEvidenceMustPass() {
        AutonomousDeliveryPhasePolicy phaseTwo = AutonomousDeliveryPhasePolicy.resolve(
                suite("PHASE_2", AutonomousDeliveryPhasePolicy.REVIEWED_READ_ONLY_ANALYZE_STUB));
        AutonomousDeliveryPhasePolicy phaseThree = AutonomousDeliveryPhasePolicy.resolve(suite("PHASE_3", null));

        assertFalse(phaseTwo.prerequisiteEvidencePassed(Map.of("required", false), Map.of("required", false)));
        assertFalse(phaseTwo.prerequisiteEvidencePassed(
                Map.of("required", true, "passed", false), Map.of("required", false)));
        assertTrue(phaseTwo.prerequisiteEvidencePassed(
                Map.of("required", true, "passed", true), Map.of("required", false)));
        assertFalse(phaseThree.prerequisiteEvidencePassed(Map.of("required", false), Map.of("required", false)));
        assertTrue(phaseThree.prerequisiteEvidencePassed(
                Map.of("required", false), Map.of("required", true, "passed", true)));
    }

    private static AutonomousDeliverySuiteManifest suite(String phase, String readOnlyAnalyzeStubId) {
        return new AutonomousDeliverySuiteManifest(
                1,
                "suite-v1",
                AutonomousDeliveryCaseCatalog.EXPECTED_CATALOG_ID,
                phase,
                "autonomous-delivery-v1",
                "hidden-v1",
                readOnlyAnalyzeStubId,
                new AutonomousDeliverySuiteManifest.Budget(60_000, 10, 20, 5, 1),
                List.of(new AutonomousDeliverySuiteManifest.CaseSelection("01", 1, true)));
    }
}
