package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.haifa.agent.testing.evidence.EvidenceSecretScanner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomousDeliveryRepeatEvidenceCollectorTest {
    @Test
    void assemblesStableResultAndSummaryFromEvaluatedEvidence() {
        Map<String, Object> usage =
                Map.of("modelCalls", 2L, "toolCalls", 4L, "wallTimeMillis", 1250L, "costKnown", false);

        AutonomousDeliveryRepeatEvidenceCollector.Result result = AutonomousDeliveryRepeatEvidenceCollector.assemble(
                input(true, clientContract(true)), new EvidenceSecretScanner.Result(1, true, List.of()), usage);

        assertTrue(result.gatePassed());
        assertEquals(true, result.resultArtifact().get("successful"));
        assertEquals("GATE_PASSED", result.resultArtifact().get("nativeStatus"));
        assertEquals("PASS", result.resultArtifact().get("hiddenAcceptance"));
        assertEquals(usage, result.resultArtifact().get("usage"));
        assertEquals("01", result.summary().get("caseId"));
        assertEquals(4L, result.summary().get("toolCalls"));
        assertEquals("PASS", result.summary().get("failureAtomicity"));
        assertEquals(true, result.summary().get("gatePassed"));
        assertEquals("GATE_PASSED", result.summary().get("nativeStatus"));
    }

    @Test
    void clientOrSecretFailureCannotBecomeGatePass() {
        AutonomousDeliveryRepeatEvidenceCollector.Result clientFailure =
                AutonomousDeliveryRepeatEvidenceCollector.assemble(
                        input(true, clientContract(false)),
                        new EvidenceSecretScanner.Result(1, true, List.of()),
                        Map.of());
        AutonomousDeliveryRepeatEvidenceCollector.Result secretFailure =
                AutonomousDeliveryRepeatEvidenceCollector.assemble(
                        input(true, clientContract(true)),
                        new EvidenceSecretScanner.Result(1, false, List.of("driver.log")),
                        Map.of());

        assertFalse(clientFailure.gatePassed());
        assertFalse(secretFailure.gatePassed());
        assertEquals(false, clientFailure.resultArtifact().get("successful"));
        assertEquals(false, secretFailure.summary().get("gatePassed"));
    }

    private static AutonomousDeliveryRepeatEvidenceCollector.Input input(
            boolean preliminaryGatePassed, CodingClientExecutionContract clientContract) {
        AutonomousDeliveryRuntimeEvidenceReader.Evidence runtime = new AutonomousDeliveryRuntimeEvidenceReader.Evidence(
                "COMPLETED",
                100,
                20,
                2,
                4,
                0,
                0,
                2,
                true,
                true,
                2,
                0,
                2,
                List.of(),
                List.of(Map.of("iteration", 1)),
                true);
        return new AutonomousDeliveryRepeatEvidenceCollector.Input(
                new AutonomousDeliveryRepeatEvidenceCollector.CaseMetadata(
                        "01", "2.0.0", "JAVA", "BUG_FIX", List.of("EDIT", "VERIFY"), List.of("FAILURE_ATOMICITY")),
                1,
                clientContract,
                1.25,
                1250,
                true,
                Map.of("schemaVersion", 1, "passed", true),
                true,
                false,
                preliminaryGatePassed,
                runtime,
                2,
                true,
                true,
                true,
                "PASS");
    }

    private static CodingClientExecutionContract clientContract(boolean passed) {
        return new CodingClientExecutionContract(
                true,
                true,
                true,
                true,
                passed,
                "COMPLETED",
                5,
                "a".repeat(64),
                "b".repeat(64),
                passed ? List.of() : List.of("CLIENT_CLOSE_FAILED"));
    }
}
