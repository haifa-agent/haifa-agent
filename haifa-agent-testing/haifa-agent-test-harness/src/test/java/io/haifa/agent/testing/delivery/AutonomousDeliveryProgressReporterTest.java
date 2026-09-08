package io.haifa.agent.testing.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.haifa.agent.runtime.api.RunEventPayloads;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutonomousDeliveryProgressReporterTest {
    @Test
    void reportsCaseContextAndPhaseProgressWithoutProjectingEventContent() {
        List<String> output = new ArrayList<>();
        AutonomousDeliveryProgressReporter reporter = new AutonomousDeliveryProgressReporter(output::add, suite());

        reporter.phaseStarted("PHASE_1");
        reporter.caseStarted("04", 1);
        reporter.project(new RunEventPayloads.RunLifecycle("RUNNING", 1, "NONE"));
        reporter.caseCompleted("04", 1, true, true, 1234);
        reporter.caseStarted("09", 2);
        reporter.project(new RunEventPayloads.ToolLifecycle(
                "tool-1", "execution.run", "STARTED", "NONE", "ignored summary", "ignored output"));

        assertEquals("[delivery] phase=PHASE_1 status=STARTED", output.get(0));
        assertTrue(output.contains("[delivery] phase=PHASE_1 case=04 repetition=1/1 status=STARTED"));
        assertTrue(output.contains("[delivery] phase=PHASE_1 case=04 repetition=1/1 run status=RUNNING reason=NONE"));
        assertTrue(output.contains(
                "[delivery-progress] phase=PHASE_1 evaluated=1/3 passed=1/1 currentCase=- currentRepetition=-"));
        assertTrue(output.contains(
                "[delivery] phase=PHASE_1 case=09 repetition=2/2 tool=execution.run status=STARTED reason=NONE"));
        assertTrue(output.contains(
                "[delivery-progress] phase=PHASE_1 evaluated=1/3 passed=1/1 currentCase=09 currentRepetition=2/2"));
        assertTrue(
                output.stream().noneMatch(line -> line.contains("ignored summary") || line.contains("ignored output")));
    }

    @Test
    void countsOnlyCompletedGatePassesAsPassed() {
        List<String> output = new ArrayList<>();
        AutonomousDeliveryProgressReporter reporter = new AutonomousDeliveryProgressReporter(output::add, suite());

        reporter.phaseStarted("PHASE_1");
        reporter.caseStarted("09", 1);
        reporter.caseCompleted("09", 1, false, false, 2000);

        assertTrue(output.contains(
                "[delivery-progress] phase=PHASE_1 evaluated=1/3 passed=0/1 currentCase=- currentRepetition=-"));
    }

    private static AutonomousDeliverySuiteManifest suite() {
        return new AutonomousDeliverySuiteManifest(
                1,
                "progress-test",
                AutonomousDeliveryCaseCatalog.EXPECTED_CATALOG_ID,
                "PHASE_1",
                "matrix",
                "hidden",
                "analyze",
                new AutonomousDeliverySuiteManifest.Budget(1, 1, 1, 1, 1),
                List.of(
                        new AutonomousDeliverySuiteManifest.CaseSelection("04", 1, true),
                        new AutonomousDeliverySuiteManifest.CaseSelection("09", 2, true)));
    }
}
