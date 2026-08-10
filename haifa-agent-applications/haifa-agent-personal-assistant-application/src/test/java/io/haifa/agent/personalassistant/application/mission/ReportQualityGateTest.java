package io.haifa.agent.personalassistant.application.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReportQualityGateTest {
    private final ReportQualityGate gate = new ReportQualityGate();

    @Test
    void acceptsACompleteMarkedReportWithRealTaskAndSourceIds() {
        assertThat(gate.evaluate(validReport(), List.of("evidence-task"), Set.of("source-1"))
                        .passed())
                .isTrue();
    }

    @Test
    void returnsStableFailuresForEachDeterministicBoundary() {
        assertFailure("", "REPORT_EMPTY");
        assertFailure("x".repeat(ReportQualityGate.MAX_REPORT_BYTES + 1), "REPORT_TOO_LARGE");
        assertFailure(
                validReport().replace("<!-- haifa-section: synthesis -->", ""), "REPORT_REQUIRED_SECTION_MISSING");
        assertFailure(
                validReport()
                        .replace(
                                "Integrated analysis connects the evidence and counterevidence into a bounded judgment.",
                                ""),
                "REPORT_SECTION_EMPTY");
        assertThat(gate.evaluate(validReport(), List.of("missing-task"), Set.of("source-1"))
                        .failureCodes())
                .contains("REPORT_TASK_COVERAGE_MISSING");
        assertFailure(validReport().replace("[[source-1]]", ""), "REPORT_SOURCES_MISSING");
        assertFailure(validReport().replace("[[source-1]]", "[[unknown-source]]"), "REPORT_CITATION_INVALID");
        assertFailure("<!-- haifa-section: executive-summary --> status claim-1 source-1", "REPORT_ONLY_METADATA");
    }

    private void assertFailure(String report, String code) {
        assertThat(gate.evaluate(report, List.of("evidence-task"), Set.of("source-1"))
                        .failureCodes())
                .contains(code);
    }

    private static String validReport() {
        return """
                # Investigation report
                <!-- haifa-section: executive-summary -->
                ## Executive summary
                The investigated claim is partly supported, while important technical and commercial limitations remain.
                <!-- haifa-section: scope-method -->
                ## Scope and method
                The review compares official material with independent evidence and records the remaining uncertainty.
                <!-- haifa-section: task-findings -->
                ## Task findings
                <!-- haifa-task: evidence-task -->
                ### Product capability
                The primary finding is supported by fetched evidence [[source-1]], with contrary observations disclosed.
                <!-- haifa-section: synthesis -->
                ## Integrated analysis
                Integrated analysis connects the evidence and counterevidence into a bounded judgment.
                <!-- haifa-section: conclusions -->
                ## Conclusions
                Treat the promotional claim as conditional and verify the stated constraints before making a decision.
                <!-- haifa-section: risks-unknowns -->
                ## Risks and unknowns
                Recent product changes, undisclosed costs, and unavailable benchmark details remain material unknowns.
                <!-- haifa-section: sources -->
                ## Sources
                - [[source-1]] Official and independently fetched evidence used for this investigation.
                """;
    }
}
