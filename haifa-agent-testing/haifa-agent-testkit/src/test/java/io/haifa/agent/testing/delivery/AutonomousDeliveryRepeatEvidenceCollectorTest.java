package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.haifa.agent.testing.evidence.EvidenceSecretScanner;
import io.haifa.agent.testing.process.ProcessTreeCleanup;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomousDeliveryRepeatEvidenceCollectorTest {
    @Test
    void assemblesStableResultAndSummaryFromEvaluatedEvidence() {
        Map<String, Object> usage =
                Map.of("modelCalls", 2L, "toolCalls", 4L, "wallTimeMillis", 1250L, "costKnown", false);

        AutonomousDeliveryRepeatEvidenceCollector.Result result = AutonomousDeliveryRepeatEvidenceCollector.assemble(
                input(true, new ProcessTreeCleanup.Result(true, 2, 0, 0, false, true)),
                new EvidenceSecretScanner.Result(1, true, List.of()),
                usage);

        assertTrue(result.gatePassed());
        assertEquals(true, result.resultArtifact().get("successful"));
        assertEquals("PASS", result.resultArtifact().get("hiddenAcceptance"));
        assertEquals(usage, result.resultArtifact().get("usage"));
        assertEquals("01", result.summary().get("caseId"));
        assertEquals(4L, result.summary().get("toolCalls"));
        assertEquals("PASS", result.summary().get("failureAtomicity"));
        assertEquals(true, result.summary().get("gatePassed"));
    }

    @Test
    void processOrSecretFailureCannotBecomeGatePass() {
        AutonomousDeliveryRepeatEvidenceCollector.Result processFailure =
                AutonomousDeliveryRepeatEvidenceCollector.assemble(
                        input(true, new ProcessTreeCleanup.Result(true, 2, 1, 0, false, false)),
                        new EvidenceSecretScanner.Result(1, true, List.of()),
                        Map.of());
        AutonomousDeliveryRepeatEvidenceCollector.Result secretFailure =
                AutonomousDeliveryRepeatEvidenceCollector.assemble(
                        input(true, new ProcessTreeCleanup.Result(true, 2, 0, 0, false, true)),
                        new EvidenceSecretScanner.Result(1, false, List.of("driver.log")),
                        Map.of());

        assertFalse(processFailure.gatePassed());
        assertFalse(secretFailure.gatePassed());
        assertEquals(false, processFailure.resultArtifact().get("successful"));
        assertEquals(false, secretFailure.summary().get("gatePassed"));
    }

    private static AutonomousDeliveryRepeatEvidenceCollector.Input input(
            boolean preliminaryGatePassed, ProcessTreeCleanup.Result cleanup) {
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
                0,
                new TerminalDriverResultContract.Validation(true, List.of()),
                1.25,
                1250,
                true,
                Map.of("schemaVersion", 1, "passed", true),
                true,
                false,
                preliminaryGatePassed,
                runtime,
                cleanup,
                2,
                true,
                true,
                true,
                "PASS");
    }
}
