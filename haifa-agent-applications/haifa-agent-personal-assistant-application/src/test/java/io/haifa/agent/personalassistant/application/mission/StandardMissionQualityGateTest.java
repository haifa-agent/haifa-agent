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
        result.put("answerMarkdown", "### Analysis\n\nEthereum upgrade analysis provides scalability and security improvements.");
        result.put("completionKind", "COMPLETE");
        result.putArray("completedItems").add("Architecture review").add("Upgrade roadmap");
        result.putArray("failedItems");
        result.putArray("sourceRefs").add("https://ethereum.org");

        StandardMissionQualityGate.Result evaluation = gate.evaluate(result, List.of("Architecture review"), List.of());
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
                .contains(
                        "STANDARD_ANSWER_TOO_SHORT",
                        "STANDARD_COMPLETION_KIND_CONTRADICTS_FAILED_ITEMS");
    }
}
