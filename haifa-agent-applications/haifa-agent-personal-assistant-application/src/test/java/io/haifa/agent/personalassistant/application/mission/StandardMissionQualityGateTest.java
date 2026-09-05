package io.haifa.agent.personalassistant.application.mission;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class StandardMissionQualityGateTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void passesValidStandardResult() {
        StandardMissionQualityGate gate = new StandardMissionQualityGate();
        ObjectNode result = JSON.createObjectNode();
        result.put("schemaVersion", "pa.mission-final-result/v2");
        result.put(
                "answerMarkdown",
                "### Architecture review\n\nArchitecture review "
                        + "provides a complete comparison of scalability, security, operational trade-offs, migration "
                        + "steps, failure handling, observability, and rollout recommendations. The analysis preserves "
                        + "the acceptance criterion Production readiness and explains the evidence behind each conclusion. "
                        + "Additional implementation detail covers capacity planning, recovery behavior, compatibility, "
                        + "cost controls, and the validation required before production adoption.");
        result.put("completionKind", "COMPLETE");
        result.putArray("completedItems").add("Architecture review").add("Production readiness");
        result.putArray("failedItems");
        result.putArray("sourceRefs").add("https://ethereum.org");

        StandardMissionQualityGate.Result evaluation = gate.evaluate(
                result,
                List.of("task result".repeat(400)),
                List.of("Architecture review"),
                List.of("Production readiness"));
        assertThat(evaluation.passed()).isTrue();
    }

    @Test
    void detectsShortAnswerOrConflictingCompletionKind() {
        StandardMissionQualityGate gate = new StandardMissionQualityGate();
        ObjectNode result = JSON.createObjectNode();
        result.put("schemaVersion", "pa.mission-final-result/v2");
        result.put("answerMarkdown", "Too short");
        result.put("completionKind", "COMPLETE");
        result.putArray("completedItems");
        result.putArray("failedItems").add("Failed step");

        StandardMissionQualityGate.Result evaluation = gate.evaluate(result, List.of(), List.of());
        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.failureCodes())
                .contains("STANDARD_ANSWER_TOO_SHORT", "STANDARD_COMPLETION_KIND_CONTRADICTS_FAILED_ITEMS");
    }

    @Test
    void requiresV2TaskAndAcceptanceCoverage() {
        StandardMissionQualityGate gate = new StandardMissionQualityGate();
        ObjectNode result = JSON.createObjectNode();
        result.put("schemaVersion", "pa.mission-final-result/v2");
        result.put("answerMarkdown", "A".repeat(400));
        result.put("completionKind", "COMPLETE");
        result.putArray("completedItems").add("Different item");
        result.putArray("failedItems");

        StandardMissionQualityGate.Result evaluation = gate.evaluate(
                result, List.of("settled result"), List.of("Architecture review"), List.of("Production readiness"));

        assertThat(evaluation.failureCodes())
                .contains("STANDARD_TASK_COVERAGE_MISSING", "STANDARD_ACCEPTANCE_COVERAGE_MISSING");
    }
}
